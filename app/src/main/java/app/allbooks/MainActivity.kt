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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.allbooks.net.Net
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

private data class Catalog(
    val name: String,
    val note: String,
    val urls: List<String>,
    val user: String = "",
    val pass: String = "",
    val noteEn: String = note,
    val noteRu: String = note
) {
    fun noteFor(lang: String): String = when (lang) {
        "en" -> noteEn
        "ru" -> noteRu
        else -> note
    }
}

// Тільки перевірені 25.11.2026 каталоги. Видалено: Gutenberg — весь сайт дає 503,
// ManyBooks — 403, OAPEN — OPDS-адреса дає 404.
private val CATALOGS = listOf(
    Catalog(
        "Flibusta",
        "Російськомовна та українська бібліотека (http)",
        listOf("http://flibusta.is/opds"),
        noteEn = "Russian- and Ukrainian-language library (http)",
        noteRu = "Русскоязычная и украинская библиотека (http)"
    ),
    Catalog(
        "CoolLib",
        "Російськомовна та українська бібліотека",
        listOf("https://coollib.com/opds"),
        noteEn = "Russian- and Ukrainian-language library",
        noteRu = "Русскоязычная и украинская библиотека"
    ),
    Catalog(
        "unglue.it",
        "Книги під вільними ліцензіями",
        listOf("https://unglue.it/api/opds/"),
        noteEn = "Books under free licenses",
        noteRu = "Книги под свободными лицензиями"
    )
)

private object CustomStore {
    private const val PREFS = "allbooks"
    private const val KEY = "custom_catalogs"

    fun load(c: Context): List<Catalog> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { line ->
            // Формат: назва|адреса|логін|пароль. Старі записи без логіна/пароля теж читаються.
            val parts = line.split('|')
            if (parts.size < 2 || parts[0].isBlank()) null
            else {
                val url = parts[1]
                Catalog(
                    parts[0], url, listOf(url),
                    user = parts.getOrNull(2) ?: "",
                    pass = parts.getOrNull(3) ?: ""
                )
            }
        }
    }

    fun save(c: Context, list: List<Catalog>) {
        val raw = list.joinToString("\n") {
            val clean = { s: String -> s.replace("|", " ").replace("\n", " ").trim() }
            clean(it.name) + "|" + clean(it.urls.first()) + "|" + clean(it.user) + "|" + it.pass.replace("|", " ").replace("\n", " ")
        }
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
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    var languagePref by remember { mutableStateOf(LanguageStore.load(context)) }
    val lang = AppLanguages.resolve(languagePref)
    val s = AppLanguages.stringsFor(lang)
    var showSettings by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var editingCatalog by remember { mutableStateOf<Catalog?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var currentCatalog by remember { mutableStateOf<Catalog?>(null) }
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
                val client = Net.buildClient(settings)
                val r = Net.fetchFirst(client, urls, currentCatalog?.user, currentCatalog?.pass)
                OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e.message == "Немає жодної адреси") s.noAddress else e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    BackHandler(enabled = history.isNotEmpty()) {
        history.removeAt(history.lastIndex)
        if (history.isEmpty()) currentCatalog = null
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showDownloads) { showDownloads = false }
    if (showSettings) {
        SettingsScreen(
            s = s,
            settings = settings,
            language = languagePref,
            onLanguageChange = { languagePref = it; LanguageStore.save(context, it) },
            onSettingsChange = { settings = it; SettingsStore.save(context, it) },
            onBack = { showSettings = false }
        )
        return
    }
    if (showDownloads) {
        DownloadsScreen(s = s, onBack = { showDownloads = false })
        return
    }
    BackHandler(enabled = selected != null) {
        selected = null
    }

    if (showAdd || editingCatalog != null) {
        CatalogDialog(
            s = s,
            initial = editingCatalog,
            onDismiss = { showAdd = false; editingCatalog = null },
            onSave = { name, url, user, pass ->
                var u = url.trim()
                if (u.isNotEmpty() && !u.startsWith("http", ignoreCase = true)) {
                    u = "https://$u"
                }
                val n = name.replace("|", " ").replace("\n", " ").trim().ifEmpty { u }
                if (u.length > 8) {
                    val cat = Catalog(n, u, listOf(u), user.trim(), pass)
                    val old = editingCatalog
                    if (old != null) {
                        custom = custom.map { if (it == old) cat else it }
                        if (currentCatalog == old) currentCatalog = cat
                    } else {
                        custom = custom + cat
                    }
                    CustomStore.save(context, custom)
                    showAdd = false
                    editingCatalog = null
                }
            }
        )
    }

    val sel = selected
    val title = when {
        sel != null -> sel.title
        current == null -> s.appNameRunes
        else -> feed?.title ?: s.loading
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (current == null && sel == null) {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gear_old),
                                contentDescription = s.menu
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(s.downloads) },
                                onClick = { showMenu = false; showDownloads = true }
                            )
                            DropdownMenuItem(
                                text = { Text(s.settings) },
                                onClick = { showMenu = false; showSettings = true }
                            )
                            DropdownMenuItem(
                                text = { Text(s.addCatalog) },
                                onClick = { showMenu = false; showAdd = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val f = feed
            when {
                sel != null -> BookDetail(
                    s = s,
                    e = sel,
                    onAuthor = { a ->
                        val target = authorTarget(a, sel, f?.searchUrl)
                        if (target != null) {
                            selected = null
                            history.add(listOf(target))
                        } else {
                            Toast.makeText(
                                context,
                                s.authorUnavailable,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onSeries = { url ->
                        selected = null
                        history.add(listOf(url))
                    },
                    onDownload = { link -> Downloads.start(context, sel, link, s, currentCatalog?.user, currentCatalog?.pass) }
                )
                current == null -> HomeList(
                    s = s,
                    lang = lang,
                    custom = custom,
                    onOpen = { c -> currentCatalog = c; history.add(c.urls) },
                    onDelete = { c ->
                        custom = custom - c
                        CustomStore.save(context, custom)
                    },
                    onEdit = { c -> editingCatalog = c }
                )
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("${s.errorTitle}: $error")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadKey++ }) { Text(s.retry) }
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
                            placeholder = { Text(s.search) },
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
                                onDownload = { link -> Downloads.start(context, e, link, s, currentCatalog?.user, currentCatalog?.pass) },
                                onOpen = { target -> history.add(listOf(target)) }
                            )
                        }
                        f.nextUrl?.let { next ->
                            item {
                                TextButton(
                                    onClick = { history.add(listOf(next)) },
                                    modifier = Modifier.padding(8.dp)
                                ) { Text(s.nextPage) }
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
    s: AppStrings,
    e: Entry,
    onAuthor: (Author) -> Unit,
    onSeries: (String) -> Unit,
    onDownload: (Link) -> Unit
) {
    val cover = e.links.firstOrNull { it.rel == "http://opds-spec.org/image" }?.href
        ?: e.links.firstOrNull {
            it.rel.contains("thumbnail") || it.rel.contains("image")
        }?.href
    val downloads = e.links.filter { it.isAcquisition }.distinctBy { it.format }
    // Рядок «Серия: …» показуємо окремо, активним, тому прибираємо його з опису.
    val summaryText = e.summary.lines()
        .filterNot { it.trim().startsWith("Серия:", ignoreCase = true) }
        .joinToString("\n").trim()

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

        val seriesLink = e.seriesLink
        val seriesName = e.seriesName
        if (seriesLink != null && seriesName != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onSeries(seriesLink.href) }) {
                Text(
                    s.series + ": " + seriesName +
                        (e.seriesNumber?.let { " #$it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (summaryText.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(s.description, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(summaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CatalogDialog(
    s: AppStrings,
    initial: Catalog?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.urls?.firstOrNull() ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var pass by remember { mutableStateOf(initial?.pass ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) s.addCatalogTitle else s.editCatalogTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(s.name) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(s.opdsAddress) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text(s.loginOptional) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text(s.passwordOptional) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                if (initial == null) {
                    Text(
                        s.authNote,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url, user, pass) }) { Text(if (initial == null) s.add else s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
private fun HomeList(
    s: AppStrings,
    lang: String,
    custom: List<Catalog>,
    onOpen: (Catalog) -> Unit,
    onDelete: (Catalog) -> Unit,
    onEdit: (Catalog) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(CATALOGS) { c -> CatalogRow(s, c, lang, onOpen, null, null) }
        if (custom.isNotEmpty()) {
            item {
                Text(
                    s.myCatalogs,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)
                )
            }
            items(custom) { c -> CatalogRow(s, c, lang, onOpen, { onDelete(c) }, { onEdit(c) }) }
        }
    }
}

@Composable
private fun CatalogRow(
    s: AppStrings,
    c: Catalog,
    lang: String,
    onOpen: (Catalog) -> Unit,
    onDelete: (() -> Unit)?,
    onEdit: (() -> Unit)? = null
) {
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
                c.noteFor(lang),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (c.user.isNotBlank()) {
            Text("🔒", modifier = Modifier.padding(end = 4.dp))
        }
        if (onEdit != null) {
            TextButton(onClick = onEdit) { Text(s.edit) }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(s.delete) }
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