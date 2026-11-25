package app.allbooks

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.allbooks.net.Net
import app.allbooks.opds.OpdsParser
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatTime(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    s: AppStrings,
    settings: NetSettings,
    onBack: () -> Unit,
    onOpen: (String, Set<String>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subs by remember { mutableStateOf(SubscriptionsStore.load(context)) }
    var checking by remember { mutableStateOf(false) }

    suspend fun checkAll() {
        if (checking) return
        checking = true
        try {
            val client = Net.buildClient(settings)
            val updated = withContext(Dispatchers.IO) {
                subs.map { sub ->
                    try {
                        val r = Net.fetchFirst(
                            client, listOf(sub.url),
                            sub.user.ifBlank { null }, sub.pass.ifBlank { null }
                        )
                        val feed = OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
                        val ids = feed.entries.map { it.id }.toSet()
                        val newIds = ids - sub.knownIds
                        sub.copy(
                            knownIds = ids,
                            newIds = newIds,
                            lastCheck = System.currentTimeMillis()
                        )
                    } catch (e: Exception) {
                        sub
                    }
                }
            }
            subs = updated
            SubscriptionsStore.save(context, updated)
        } finally {
            checking = false
        }
    }

    // Перевіряємо новинки одразу при відкритті екрана.
    LaunchedEffect(Unit) { checkAll() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(s.subscriptions) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(s.back) }
                },
                actions = {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        TextButton(onClick = { scope.launch { checkAll() } }) { Text(s.checkNow) }
                    }
                }
            )
        }
    ) { padding ->
        if (subs.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Text(s.noSubscriptions, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.noSubscriptionsHint, style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(subs, key = { it.url }) { sub ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(sub.url, sub.newIds) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (sub.type == "series") s.series else s.author) + ": " + sub.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (sub.lastCheck > 0) {
                                Text(
                                    s.lastCheck + ": " + formatTime(sub.lastCheck),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (sub.newIds.isNotEmpty()) {
                                Text(
                                    s.newBooks + ": " + sub.newIds.size,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        TextButton(onClick = {
                            val rest = subs.filterNot { it.url == sub.url }
                            subs = rest
                            SubscriptionsStore.save(context, rest)
                        }) { Text(s.unsubscribe) }
                    }
                }
            }
        }
    }
}
