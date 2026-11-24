package app.allbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.allbooks.net.Net
import app.allbooks.net.NetSettings
import app.allbooks.opds.Entry
import app.allbooks.opds.Feed
import app.allbooks.opds.OpdsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

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
        "Feedbooks",
        "Публічне надбання та оригінальні книги",
        listOf("https://catalog.feedbooks.com/catalog/index.atom")
    ),
    Catalog(
        "Internet Archive",
        "Open Library, велика цифрова бібліотека",
        listOf("https://bookserver.archive.org/catalog/")
    ),
    Catalog(
        "Flibusta",
        "Російськомовна та українська бібліотека (http)",
        listOf("http://flibusta.is/opds")
    )
)

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
    val history = remember { mutableStateListOf<List<String>>() }
    val current = history.lastOrNull()
    var feed by remember { mutableStateOf<Feed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

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

    val title = if (current == null) "All Books" else (feed?.title ?: "Завантаження…")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val f = feed
            when {
                current == null -> HomeList { c -> history.add(c.urls) }
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Помилка: $error")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadKey++ }) { Text("Повторити") }
                }
                f != null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(f.entries) { e ->
                        EntryRow(e) { target -> history.add(listOf(target)) }
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

@Composable
private fun HomeList(onOpen: (Catalog) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(CATALOGS) { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(c) }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Text(c.note, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun EntryRow(e: Entry, onOpen: (String) -> Unit) {
    val nav = if (e.isBook) null else e.navigation
    val clickMod = if (nav != null) Modifier.clickable { onOpen(nav.href) } else Modifier

    Column(
        Modifier
            .fillMaxWidth()
            .then(clickMod)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(e.title, style = MaterialTheme.typography.titleMedium)
        if (e.authors.isNotEmpty()) {
            Text(
                e.authors.joinToString(", "),
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
            val formats = e.links.filter { it.isAcquisition }.map { it.format }.distinct()
            Text(formats.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider()
}
