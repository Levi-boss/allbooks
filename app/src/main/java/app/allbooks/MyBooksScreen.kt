package app.allbooks

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.allbooks.net.NetSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBooksScreen(s: AppStrings, settings: NetSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    var books by remember { mutableStateOf(MyBooks.list(context, settings, s)) }
    var toDelete by remember { mutableStateOf<MyBook?>(null) }

    fun reload() {
        books = MyBooks.list(context, settings, s)
    }

    if (toDelete != null) {
        val book = toDelete!!
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(s.deleteBookTitle) },
            text = { Text(book.displayName + "\n" + s.deleteBookText) },
            confirmButton = {
                TextButton(onClick = {
                    MyBooks.delete(context, book, s)
                    toDelete = null
                    reload()
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
        if (books.isEmpty()) {
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
                            Button(onClick = { MyBooks.open(context, book, s) }) {
                                Text(s.open)
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
