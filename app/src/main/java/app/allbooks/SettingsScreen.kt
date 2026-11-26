package app.allbooks

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.allbooks.net.NetSettings
import app.allbooks.net.ProxyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    s: AppStrings,
    settings: NetSettings,
    language: String,
    catalogs: List<String>,
    onLanguageChange: (String) -> Unit,
    onSettingsChange: (NetSettings) -> Unit,
    onBack: () -> Unit
) {
    var mode by remember { mutableStateOf(settings.proxyMode) }
    var host by remember { mutableStateOf(settings.proxyHost) }
    var port by remember { mutableStateOf(if (settings.proxyPort > 0) settings.proxyPort.toString() else "") }
    var user by remember { mutableStateOf(settings.proxyUser) }
    var pass by remember { mutableStateOf(settings.proxyPass) }
    var useDoh by remember { mutableStateOf(settings.useDoh) }
    var notifyNewBooks by remember { mutableStateOf(settings.notifyNewBooks) }
    var theme by remember { mutableStateOf(settings.theme) }
    var startCatalog by remember { mutableStateOf(settings.startCatalog) }
    var folder by remember { mutableStateOf(settings.downloadFolder) }
    var safFolderUri by remember { mutableStateOf(settings.safFolderUri) }
    // Запам'ятана зовнішня читалка для «Відкрити» (null — запитувати щоразу).
    var readerLabel by remember {
        mutableStateOf(
            MyBooks.savedReaderPackage(context)
                ?.let { MyBooks.readerAppLabel(context, it) }
        )
    }
    val context = LocalContext.current

    fun buildSettings(): NetSettings = NetSettings(
        proxyMode = mode,
        proxyHost = host.trim(),
        proxyPort = port.trim().toIntOrNull() ?: 0,
        proxyUser = user.trim(),
        proxyPass = pass,
        useDoh = useDoh,
        startCatalog = startCatalog,
        downloadFolder = folder.trim().ifEmpty { "BookMriya" },
        folderChosen = true,
        safFolderUri = safFolderUri,
        theme = theme,
        notifyNewBooks = notifyNewBooks
    )

    fun save() {
        onSettingsChange(buildSettings())
    }

    // Тему застосовуємо одразу, як і мову, — без кнопки «Зберегти».
    fun selectTheme(value: String) {
        theme = value
        onSettingsChange(buildSettings().copy(theme = value))
    }

    // Вибір папки через файловий менеджер (SAF): система дає застосунку
    // постійний доступ саме до вибраної папки, без дозволу на всю пам'ять.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // дозвіл не зберігся — папка все одно спробує працювати
            }
            safFolderUri = uri.toString()
            folder = Downloads.safDisplayName(uri.toString())
            onSettingsChange(
                buildSettings().copy(
                    safFolderUri = uri.toString(),
                    downloadFolder = Downloads.safDisplayName(uri.toString())
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.settings) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(s.back) }
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
            Text(s.language, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for ((code, label) in listOf(
                AppLanguages.AUTO to s.languageAuto,
                AppLanguages.EN to "English",
                AppLanguages.UK to "Українська",
                AppLanguages.RU to "Русский"
            )) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onLanguageChange(code) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = language == code, onClick = { onLanguageChange(code) })
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text(
                s.languageNote,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
            Text(s.themeTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for ((value, label) in listOf(
                "system" to s.themeSystem,
                "light" to s.themeLight,
                "dark" to s.themeDark
            )) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectTheme(value) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = theme == value, onClick = { selectTheme(value) })
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(s.startPage, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for ((value, label) in listOf("" to s.startPageHome) + catalogs.map { it to it }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { startCatalog = value }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = startCatalog == value, onClick = { startCatalog = value })
                    Text(label, modifier = Modifier.padding(start = 8.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(s.downloadFolder, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                singleLine = true,
                label = { Text(s.downloadFolder) },
                placeholder = { Text("BookMriya") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                s.downloadFolderHint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { folderPicker.launch(null) }) {
                    Text(s.chooseFolder)
                }
                if (safFolderUri.isNotBlank()) {
                    TextButton(onClick = {
                        safFolderUri = ""
                        folder = "BookMriya"
                        onSettingsChange(buildSettings().copy(safFolderUri = "", downloadFolder = "BookMriya"))
                    }) { Text(s.resetFolder) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(s.proxy, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for ((m, label) in listOf(
                ProxyMode.NONE to s.proxyOff,
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
                    label = { Text(s.proxyHost) },
                    placeholder = { Text(s.proxyHostHint) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text(s.proxyPort) },
                    placeholder = { Text(s.proxyPortHint) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    label = { Text(s.proxyLoginOptional) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    label = { Text(s.proxyPasswordOptional) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    s.proxyNote,
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
                    Text(s.dohTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.dohDesc,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = useDoh, onCheckedChange = { useDoh = it })
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.notifyNewBooksTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.notifyNewBooksDesc,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = notifyNewBooks, onCheckedChange = { notifyNewBooks = it })
            }

            Spacer(Modifier.height(24.dp))
            Text(s.externalReaderTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                readerLabel ?: s.askEveryTime,
                style = MaterialTheme.typography.bodyMedium
            )
            if (readerLabel != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    MyBooks.clearReaderPackage(context)
                    readerLabel = null
                }) { Text(s.resetReaderChoice) }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    save()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(s.save) }
            Spacer(Modifier.height(8.dp))
            Text(
                s.settingsNote,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, AppLog.text())
                    }
                    context.startActivity(Intent.createChooser(send, s.shareLogs))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(s.shareLogs) }
        }
    }
}
