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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(s: AppStrings, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(Downloads.list(context, s)) }

    // Оновлення прогресу, поки екран відкритий
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            items = Downloads.list(context, s)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.downloads) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(s.back) }
                },
                actions = {
                    TextButton(onClick = { items = Downloads.list(context, s) }) { Text(s.refresh) }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.downloadsEmpty)
                Spacer(Modifier.height(8.dp))
                Text(
                    s.downloadsEmptyHint,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(items, key = { it.id }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            item.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        if (item.total > 0) {
                            LinearProgressIndicator(
                                progress = { item.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${item.statusText(s)} — ${item.percent}% (${item.downloaded / 1024} / ${item.total / 1024} ${s.kb})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(item.statusText(s), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.isSuccessful) {
                                Button(onClick = { Downloads.open(context, item.id, s) }) {
                                    Text(s.open)
                                }
                            }
                            if (item.isFailed && item.canRetry) {
                                Button(onClick = {
                                    Downloads.retry(context, item.id, s)
                                    items = Downloads.list(context, s)
                                }) { Text(s.retry) }
                            }
                            if (item.isActive) {
                                OutlinedButton(onClick = {
                                    Downloads.remove(context, item.id)
                                    items = Downloads.list(context, s)
                                }) { Text(s.cancelDownload) }
                            } else {
                                OutlinedButton(onClick = {
                                    Downloads.remove(context, item.id)
                                    items = Downloads.list(context, s)
                                }) { Text(s.removeFromList) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
