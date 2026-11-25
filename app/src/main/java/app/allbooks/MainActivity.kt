package app.allbooks

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.allbooks.net.Net
import app.allbooks.net.NetSettings
import app.allbooks.opds.Author
import app.allbooks.opds.Entry
import app.allbooks.opds.Feed
import app.allbooks.opds.Link
import app.allbooks.opds.OpdsParser
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URLEncoder

private data class Catalog(val name: String, val note: String, val urls: List<String>)

private val CATALOGS = listOf(
    Catalog(
        "Project Gutenberg",
        "Публічне надбання, понад 70 000 книг",
        listOf(
            "https://www.gutenberg.org/ebooks/search.opds/",
            "https://m.gutenberg.org/ebooks.opds/"
        )
    ),
    Catalog(
        "Flibusta",
        "Російськомовна та українська бібліотека (http)",
        listOf("http://flibusta.is/opds")
    ),
    Catalog(
        "ManyBooks",
        "Безкоштовні книги (адресу ще не перевірено)",
        listOf("https://manybooks.net/opds/index.php")
    ),
    Catalog(
        "OAPEN",
        "Відкриті наукові книги (адресу ще не перевірено)",
        listOf("https://library.oapen.org/opds")
    ),
    Catalog(
        "unglue.it",
        "Книги під вільними ліцензіями (адресу ще не перевірено)",
        listOf("https://unglue.it/api/opds/")
    )
)

private object CustomStore {
    private const val PREFS = "allbooks"
    private const val KEY = "custom_catalogs"

    fun load(c: Context): List<Catalog> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) null
            else {
                val url = line.substring(i + 1)
                Catalog(line.substring(0, i), url, listOf(url))
            }
        }
    }

    fun save(c: Context, list: List<Catalog>) {
        val raw = list.joinToString("\n") { it.name + "|" + it.urls.first() }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }
}

private fun searchUrlFor(template: String, term: String): String {
    val enc = URLEncoder.encode(term, "UTF-8")
    return template.replace("{searchTerms}", enc).replace("%7BsearchTerms%7D", enc)
}

/** Шукає адресу сторінки з книгами автора: пов'язане посилання, uri автора або пошук. */
private fun authorTarget(a: Author, e: Entry, searchTemplate: String?): String? {
    val nameLc = a.name.lowercase()
    val atomLinks = e.links.filter { it.type.contains("atom") }

    atomLinks.firstOrNull { it.title.lowercase().contains(nameLc) }?.let { return it.href }

    if (e.authors.size == 1) {
        atomLinks.firstOrNull {
            val t = it.title.lowercase()
            t.contains("автор") || t.contains("author")
        }?.let { return it.href }
    }

    val u = a.uri
    if (u.startsWith("http") && (u.contains("opds") || u.contains("author"))) return u

    if (searchTemplate != null) return searchUrlFor(searchTemplate, a.name)
    return null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CatalogScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen() {
    val context = LocalContext.current
    val history = remember { mutableStateListOf<List<String>>() }
    val current = history.lastOrNull()
    var feed by remember { mutableStateOf<Feed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(CustomStore.load(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Entry?>(null) }

    LaunchedEffect(current, reloadKey) {
        val urls = current ?: return@LaunchedEffect
        loading = true
        error = null
        feed = null
        try {
            feed = withContext(Dispatchers.IO) {
                val client = Net.buildClient(NetSettings())
                val r = Net.fetchFirst(client, urls)
                OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    BackHandler(enabled = history.isNotEmpty()) {
        history.removeAt(history.lastIndex)
    }
    BackHandler(enabled = selected != null) {
        selected = null
    }

    if (showAdd) {
        AddCatalogDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, url ->
                var u = url.trim()
                if (u.isNotEmpty() && !u.startsWith("http", ignoreCase = true)) {
                    u = "https://$u"
                }
                val n = name.replace("|", " ").replace("\n", " ").trim().ifEmpty { u }
                if (u.length > 8) {
                    custom = custom + Catalog(n, u, listOf(u))
                    CustomStore.save(context, custom)
                    showAdd = false
                }
            }
        )
    }

    val sel = selected
    val title = when {
        sel != null -> sel.title
        current == null -> "All Books"
        else -> feed?.title ?: "Завантаження…"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (current == null && sel == null) {
                        TextButton(onClick = { showAdd = true }) { Text("+ Додати") }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val f = feed
            when {
                sel != null -> BookDetail(
                    e = sel,
                    onAuthor = { a ->
                        val target = authorTarget(a, sel, f?.searchUrl)
                        if (target != null) {
                            selected = null
                            history.add(listOf(target))
                        } else {
                            Toast.makeText(
                                context,
                                "Для цього каталогу перехід по автору недоступний",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onDownload = { link -> Downloads.start(context, sel, link) }
                )
                current == null -> HomeList(
                    custom = custom,
                    onOpen = { c -> history.add(c.urls) },
                    onDelete = { c ->
                        custom = custom - c
                        CustomStore.save(context, custom)
                    }
                )
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Помилка: $error")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadKey++ }) { Text("Повторити") }
                }
                f != null -> Column(Modifier.fillMaxSize()) {
                    f.searchUrl?.let { template ->
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            singleLine = true,
                            placeholder = { Text("Пошук") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                val q = query.trim()
                                if (q.isNotEmpty()) {
                                    history.add(listOf(searchUrlFor(template, q)))
                                }
                            })
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(f.entries) { e ->
                            EntryRow(
                                e,
                                onBook = { selected = e },
                                onDownload = { link -> Downloads.start(context, e, link) },
                                onOpen = { target -> history.add(listOf(target)) }
                            )
                        }
                        f.nextUrl?.let { next ->
                            item {
                                TextButton(
                                    onClick = { history.add(listOf(next)) },
                                    modifier = Modifier.padding(8.dp)
                                ) { Text("Далі →") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookDetail(
    e: Entry,
    onAuthor: (Author) -> Unit,
    onDownload: (Link) -> Unit
) {
    val cover = e.links.firstOrNull { it.rel == "http://opds-spec.org/image" }?.href
        ?: e.links.firstOrNull {
            it.rel.contains("thumbnail") || it.rel.contains("image")
        }?.href
    val downloads = e.links.filter { it.isAcquisition }.distinctBy { it.format }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row {
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(130.dp).heightIn(max = 200.dp)
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                e.authors.forEach { a ->
                    TextButton(onClick = { onAuthor(a) }) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        if (downloads.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                downloads.forEach { l ->
                    Button(
                        onClick = { onDownload(l) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("⬇ " + l.format.uppercase()) }
                }
            }
        }

        if (e.summary.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("Опис", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(e.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AddCatalogDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати каталог") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Назва") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("OPDS-адреса") }
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url) }) { Text("Додати") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
private fun HomeList(
    custom: List<Catalog>,
    onOpen: (Catalog) -> Unit,
    onDelete: (Catalog) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(CATALOGS) { c -> CatalogRow(c, onOpen, null) }
        if (custom.isNotEmpty()) {
            item {
                Text(
                    "Мої каталоги",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)
                )
            }
            items(custom) { c -> CatalogRow(c, onOpen) { onDelete(c) } }
        }
    }
}

@Composable
private fun CatalogRow(c: Catalog, onOpen: (Catalog) -> Unit, onDelete: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(c) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            Text(
                c.note,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text("Видалити") }
        }
    }
    HorizontalDivider()
}

@Composable
fun EntryRow(
    e: Entry,
    onBook: () -> Unit,
    onDownload: (Link) -> Unit,
    onOpen: (String) -> Unit
) {
    val nav = if (e.isBook) null else e.navigation
    val clickMod = when {
        e.isBook -> Modifier.clickable { onBook() }
        nav != null -> Modifier.clickable { onOpen(nav.href) }
        else -> Modifier
    }
    val cover = e.links.firstOrNull {
        it.rel.contains("thumbnail") || it.rel.contains("image")
    }?.href

    Row(
        Modifier
            .fillMaxWidth()
            .then(clickMod)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(56.dp).height(80.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleMedium)
            if (e.authors.isNotEmpty()) {
                Text(
                    e.authors.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (e.summary.isNotBlank()) {
                Text(
                    e.summary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (e.isBook) {
                val acquisitions = e.links.filter { it.isAcquisition }.distinctBy { it.format }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    acquisitions.forEach { l ->
                        TextButton(onClick = { onDownload(l) }) {
                            Text("⬇ " + l.format.uppercase())
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider()
}