package app.allbooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.allbooks.net.NetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBooksScreen(
    s: AppStrings,
    settings: NetSettings,
    onBack: () -> Unit,
    onRead: (MyBook) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Список вантажиться у фоні (Dispatchers.IO): MyBooks.list читає папку,
    // питає DownloadManager і розшифровує метадані — на головному потоці
    // це заморожувало екран на секунду-дві при кожному відкритті.
    var books by remember { mutableStateOf<List<MyBook>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toDelete by remember { mutableStateOf<MyBook?>(null) }
    var enrichJob by remember { mutableStateOf<Job?>(null) }

    // Групування за серіями (3.3): серія вже є в метаданих з кроку 4.
    // Книги однієї серії йдуть разом із заголовком (за алфавітом серій),
    // книги без серії — в кінці списку без заголовка.
    val grouped = remember(books) {
        val bySeries = books.filter { !it.meta?.series.isNullOrBlank() }
            .groupBy { it.meta?.series.orEmpty() }
            .toSortedMap()
        bySeries to books.filter { it.meta?.series.isNullOrBlank() }
    }

    fun reload() {
        enrichJob?.cancel()
        scope.launch {
            loading = true
            val loaded = withContext(Dispatchers.IO) { MyBooks.list(context, settings, s) }
            books = loaded
            loading = false
            // Метадані — у фоні, по мірі готовності: список уже на екрані
            // з іменами файлів, назви й автори підставляються потім.
            enrichJob = scope.launch {
                var current = loaded
                for (book in loaded) {
                    if (!isActive) break
                    val meta = withContext(Dispatchers.IO) { MyBooks.loadMeta(context, book) }
                    if (meta != null && isActive) {
                        current = current.map {
                            if (it.fileName == book.fileName) it.copy(meta = meta) else it
                        }
                        books = current
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // «Відкрити»: за вибором у Налаштуваннях — одразу у вбудованій
    // читалці або вибір зі списку щоразу (без запам'ятовування).
    var openPickerFor by remember { mutableStateOf<MyBook?>(null) }
    var openCandidates by remember { mutableStateOf<List<MyBooks.ReaderApp>>(emptyList()) }

    fun onOpen(book: MyBook) {
        if (MyBooks.preferBuiltIn(context)) {
            onRead(book)
            return
        }
        val candidates = MyBooks.readerCandidates(context, book)
        when {
            candidates.isEmpty() -> MyBooks.open(context, book, s)
            candidates.size == 1 -> MyBooks.open(context, book, s, candidates[0].packageName)
            else -> {
                openPickerFor = book
                openCandidates = candidates
            }
        }
    }

    if (openPickerFor != null) {
        val book = openPickerFor!!
        AlertDialog(
            onDismissRequest = { openPickerFor = null },
            title = { Text(s.openWithTitle) },
            text = {
                Column {
                    openCandidates.forEach { app ->
                        TextButton(
                            onClick = {
                                openPickerFor = null
                                MyBooks.open(context, book, s, app.packageName)
                            }
                        ) { Text(app.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { openPickerFor = null }) { Text(s.cancel) }
            }
        )
    }

    if (toDelete != null) {
        val book = toDelete!!
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(s.deleteBookTitle) },
            text = { Text(book.displayName + "\n" + s.deleteBookText) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { MyBooks.delete(context, book, s) }
                        reload()
                    }
                }) { Text(s.delete) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(s.cancel) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.myBooks) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(s.back) }
                },
                actions = {
                    TextButton(onClick = { reload() }) { Text(s.refresh) }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (books.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.myBooksEmpty)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.myBooksEmptyHint,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val (bySeries, withoutSeries) = grouped
                bySeries.forEach { (series, items) ->
                    item(key = "series:$series") {
                        Text(
                            series,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(items, key = { it.fileName }) { book ->
                        BookRow(book, s, { onOpen(it) }, { onRead(it) }, { toDelete = it })
                    }
                }
                items(withoutSeries, key = { it.fileName }) { book ->
                    BookRow(book, s, { onOpen(it) }, { onRead(it) }, { toDelete = it })
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: MyBook,
    s: AppStrings,
    onOpen: (MyBook) -> Unit,
    onRead: (MyBook) -> Unit,
    onDelete: (MyBook) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            book.meta?.title?.takeIf { it.isNotBlank() } ?: book.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val authorLine = listOfNotNull(
            book.meta?.author?.takeIf { it.isNotBlank() },
            book.meta?.series?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
        if (authorLine.isNotBlank()) {
            Text(
                authorLine,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val meta = MyBooks.formatSize(book.size, s)
        val date = MyBooks.formatDate(book.modified)
        val metaLine = listOf(meta, date).filter { it.isNotBlank() }
            .joinToString(" • ")
        if (metaLine.isNotBlank()) {
            Text(
                metaLine,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onOpen(book) }) {
                Text(s.open)
            }
            if (Reader.supports(book.fileName)) {
                TextButton(onClick = { onRead(book) }) { Text(s.read) }
            }
            TextButton(onClick = { onDelete(book) }) { Text(s.delete) }
        }
    }
    HorizontalDivider()
}
