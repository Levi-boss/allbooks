package app.allbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.allbooks.net.NetSettings
import app.allbooks.net.ProxyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: NetSettings,
    onSettingsChange: (NetSettings) -> Unit,
    onBack: () -> Unit
) {
    var mode by remember { mutableStateOf(settings.proxyMode) }
    var host by remember { mutableStateOf(settings.proxyHost) }
    var port by remember { mutableStateOf(if (settings.proxyPort > 0) settings.proxyPort.toString() else "") }
    var user by remember { mutableStateOf(settings.proxyUser) }
    var pass by remember { mutableStateOf(settings.proxyPass) }
    var useDoh by remember { mutableStateOf(settings.useDoh) }

    fun save() {
        onSettingsChange(
            NetSettings(
                proxyMode = mode,
                proxyHost = host.trim(),
                proxyPort = port.trim().toIntOrNull() ?: 0,
                proxyUser = user.trim(),
                proxyPass = pass,
                useDoh = useDoh
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Назад") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Проксі", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for ((m, label) in listOf(
                ProxyMode.NONE to "Вимкнено",
                ProxyMode.HTTP to "HTTP",
                ProxyMode.SOCKS5 to "SOCKS5"
            )) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { mode = m }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == m, onClick = { mode = m })
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            if (mode != ProxyMode.NONE) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    singleLine = true,
                    label = { Text("Хост проксі") },
                    placeholder = { Text("наприклад, 127.0.0.1") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text("Порт") },
                    placeholder = { Text("наприклад, 1080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text("Логін проксі (необов'язково)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text("Пароль проксі (необов'язково)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Без хоста і порту проксі не ввімкнеться, навіть якщо вибрано HTTP/SOCKS5.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DNS через HTTPS (DoH)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "DNS-запити через Cloudflare (1.1.1.1). Допомагає, коли провайдер блокує адреси каталогів.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = useDoh, onCheckedChange = { useDoh = it })
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    save()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Зберегти") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Налаштування застосовуються до наступного завантаження каталогу.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
