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

    fun reload() {
        scope.launch {
            loading = true
            books = withContext(Dispatchers.IO) { MyBooks.list(context, settings, s) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // «Відкрити»: при першому відкритті — вибір читалки зі списку,
    // вибір запам'ятовується і далі книга відкривається одразу в ній.
    var openPickerFor by remember { mutableStateOf<MyBook?>(null) }
    var openCandidates by remember { mutableStateOf<List<MyBooks.ReaderApp>>(emptyList()) }

    fun onOpen(book: MyBook) {
        val saved = MyBooks.savedReaderPackage(context)
        if (saved != null && MyBooks.canOpenWith(context, book, saved)) {
            MyBooks.open(context, book, s, saved)
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
                                MyBooks.saveReaderPackage(context, app.packageName)
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
                items(books, key = { it.fileName }) { book ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            book.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
                            TextButton(onClick = { toDelete = book }) { Text(s.delete) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
