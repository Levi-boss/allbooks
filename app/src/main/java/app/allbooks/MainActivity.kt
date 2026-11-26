package app.allbooks

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.allbooks.net.Net
import app.allbooks.opds.Author
import app.allbooks.opds.Entry
import app.allbooks.opds.Feed
import app.allbooks.opds.Link
import app.allbooks.opds.OpdsParser
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
        "Російськомовна та українська бібліотека",
        listOf("http://flibusta.is/opds"),
        noteEn = "Russian- and Ukrainian-language library",
        noteRu = "Русскоязычная и украинская библиотека"
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

/** Логін/пароль для каталогів окремо від їх списку: вбудовані каталоги
 *  теж можуть попросити авторизацію (401), а їх редагувати не можна. */
private object AuthStore {
    private const val PREFS = "allbooks"
    private const val KEY = "catalog_auth"

    fun load(c: Context): Map<String, Pair<String, String>> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val map = mutableMapOf<String, Pair<String, String>>()
        raw.lines().forEach { line ->
            val parts = line.split('|')
            if (parts.size >= 3 && parts[0].isNotBlank()) {
                map[parts[0]] = parts[1] to parts[2]
            }
        }
        return map
    }

    fun save(c: Context, map: Map<String, Pair<String, String>>) {
        val raw = map.entries.joinToString("\n") { (name, up) ->
            val clean = { s: String -> s.replace("|", " ").replace("\n", " ").trim() }
            clean(name) + "|" + clean(up.first) + "|" + up.second.replace("|", " ").replace("\n", " ")
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }
}

private fun searchUrlFor(template: String, term: String): String {
    val enc = URLEncoder.encode(term, "UTF-8")
    return template.replace("{searchTerms}", enc).replace("%7BsearchTerms%7D", enc)
}

/** Одна секція результатів глобального пошуку: каталог і його книги. */
private data class GlobalSection(
    val catalog: Catalog,
    val template: String?,
    val entries: List<Entry>,
    val error: String? = null
)

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
        AppLog.d("BookMriya start")
        ThemeChoice.set(SettingsStore.load(this).theme)
        // Фонова перевірка підписок (раз на день), якщо сповіщення ввімкнено.
        if (SettingsStore.load(this).notifyNewBooks) {
            SubCheckScheduler.schedule(this)
        }
        enableEdgeToEdge()
        setContent {
            val dark = themeIsDark(ThemeChoice.theme)
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CatalogScreen()
                }
            }
        }
    }
}

/** Вибір теми: "system" (як у телефоні), "light", "dark". */
object ThemeChoice {
    var theme by mutableStateOf("system")
        private set

    fun set(t: String) {
        theme = t
    }
}

@Composable
private fun themeIsDark(theme: String): Boolean = when (theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
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
    var showMyBooks by remember { mutableStateOf(false) }
    var readingBook by remember { mutableStateOf<ReaderBook?>(null) }
    var showSubscriptions by remember { mutableStateOf(false) }
    var subsVersion by remember { mutableIntStateOf(0) }
    var newIds by remember { mutableStateOf<Set<String>>(emptySet()) }
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
    var searchExpanded by remember { mutableStateOf(false) }
    var globalQuery by remember { mutableStateOf("") }
    var globalSearching by remember { mutableStateOf(false) }
    var globalSections by remember { mutableStateOf<List<GlobalSection>>(emptyList()) }
    var globalDone by remember { mutableStateOf(false) }
    var globalBookCatalog by remember { mutableStateOf<Catalog?>(null) }
    var globalBookTemplate by remember { mutableStateOf<String?>(null) }
    val searchFocus = remember { FocusRequester() }
    var custom by remember { mutableStateOf(CustomStore.load(context)) }
    var catalogAuth by remember { mutableStateOf(AuthStore.load(context)) }
    var showAuth by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    var startOpened by remember { mutableStateOf(false) }
    var folderSetupDismissed by remember { mutableStateOf(false) }
    var folderDraft by remember { mutableStateOf(settings.downloadFolder) }

    // Логін/пароль для поточного каталогу: свої поля у власного каталогу,
    // інакше збережені окремо в AuthStore (вбудовані теж можуть дати 401).
    val credUser = currentCatalog?.user?.takeIf { it.isNotBlank() }
        ?: currentCatalog?.name?.let { catalogAuth[it]?.first }
    val credPass = if (currentCatalog?.user?.isNotBlank() == true) currentCatalog?.pass
    else currentCatalog?.name?.let { catalogAuth[it]?.second }

    // Дозвіл на запис файлів (Android 12 і старіше; на 13+ його вже нема —
    // доступ дає вибір папки через файловий менеджер). Питаємо раз при запуску.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Сповіщення про прогрес завантаження (Android 13+ питає окремо).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Вибір папки через файловий менеджер (SAF) при першому запуску.
    val firstRunFolderPicker = rememberLauncherForActivityResult(
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
            val updated = settings.copy(
                downloadFolder = Downloads.safDisplayName(uri.toString()),
                folderChosen = true,
                safFolderUri = uri.toString()
            )
            settings = updated
            SettingsStore.save(context, updated)
            folderSetupDismissed = true
        }
    }

    // Стартова сторінка: відкрити вибраний каталог одразу при запуску.
    LaunchedEffect(settings.startCatalog) {
        if (!startOpened && history.isEmpty()) {
            startOpened = true
            val name = settings.startCatalog
            if (name.isNotBlank()) {
                (CATALOGS + custom).firstOrNull { it.name == name }?.let { c ->
                    currentCatalog = c
                    history.add(c.urls)
                }
            }
        }
    }

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) searchFocus.requestFocus()
    }

    LaunchedEffect(settings.theme) {
        ThemeChoice.set(settings.theme)
    }

    LaunchedEffect(current, reloadKey) {
        searchExpanded = false
        val urls = current
        if (urls == null) {
            // Додому: прибираємо стару стрічку, щоб лупа і помилки
            // відкритого каталогу не лишалися на головній.
            feed = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        feed = null
        try {
            feed = withContext(Dispatchers.IO) {
                val client = Net.buildClient(settings)
                val r = Net.fetchFirst(client, urls, credUser, credPass)
                OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e.message == "Немає жодної адреси") s.noAddress else e.message ?: e.javaClass.simpleName
            AppLog.d("Feed error: ${e.message}")
            if (e.message?.startsWith("HTTP 401") == true) showAuth = true
        }
        loading = false
    }

    BackHandler(enabled = history.isNotEmpty()) {
        history.removeAt(history.lastIndex)
        if (history.isEmpty()) {
            currentCatalog = null
            newIds = emptySet()
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showDownloads) { showDownloads = false }
    BackHandler(enabled = showMyBooks) { showMyBooks = false }
    BackHandler(enabled = showSubscriptions) { showSubscriptions = false }
    if (showSubscriptions) {
        SubscriptionsScreen(
            s = s,
            settings = settings,
            onBack = { showSubscriptions = false },
            onOpen = { url, ids ->
                showSubscriptions = false
                selected = null
                newIds = ids
                history.add(listOf(url))
            }
        )
        return
    }
    if (showSettings) {
        SettingsScreen(
            s = s,
            settings = settings,
            language = languagePref,
            catalogs = (CATALOGS + custom).map { it.name },
            onLanguageChange = { languagePref = it; LanguageStore.save(context, it) },
            onSettingsChange = {
                settings = it
                SettingsStore.save(context, it)
                if (it.notifyNewBooks) SubCheckScheduler.schedule(context)
                else SubCheckScheduler.cancel(context)
            },
            onBack = { showSettings = false }
        )
        return
    }
    if (showDownloads) {
        DownloadsScreen(s = s, onBack = { showDownloads = false })
        return
    }
    if (showMyBooks) {
        MyBooksScreen(
            s = s,
            settings = settings,
            onBack = { showMyBooks = false },
            onRead = { readingBook = ReaderBook.fromMyBook(it) }
        )
        return
    }
    if (readingBook != null) {
        ReaderScreen(
            s = s,
            book = readingBook!!,
            dark = themeIsDark(settings.theme),
            onBack = { readingBook = null }
        )
        return
    }
    BackHandler(enabled = selected != null) {
        selected = null
        globalBookCatalog = null
        globalBookTemplate = null
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

    if (showAuth && currentCatalog != null) {
        AuthDialog(
            s = s,
            catalogName = currentCatalog!!.name,
            initialUser = credUser ?: "",
            initialPass = credPass ?: "",
            onDismiss = { showAuth = false },
            onSave = { user, pass ->
                val cat = currentCatalog!!
                if (custom.any { it.name == cat.name && it.urls == cat.urls }) {
                    val updated = cat.copy(user = user, pass = pass)
                    custom = custom.map { if (it.name == cat.name && it.urls == cat.urls) updated else it }
                    CustomStore.save(context, custom)
                    currentCatalog = updated
                } else {
                    catalogAuth = catalogAuth + (cat.name to (user to pass))
                    AuthStore.save(context, catalogAuth)
                }
                AppLog.d("Auth saved for ${cat.name}")
                showAuth = false
                reloadKey++
            }
        )
    }

    // Перший запуск: якщо папку завантажень ще не вибирали — запитати одразу.
    if (!settings.folderChosen && !folderSetupDismissed) {
        AlertDialog(
            onDismissRequest = { folderSetupDismissed = true },
            title = { Text(s.firstRunFolderTitle) },
            text = {
                Column {
                    Text(s.firstRunFolderHint, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = folderDraft,
                        onValueChange = { folderDraft = it },
                        singleLine = true,
                        label = { Text(s.downloadFolder) },
                        placeholder = { Text("BookMriya") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { firstRunFolderPicker.launch(null) }) {
                        Text(s.chooseFolder)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = folderDraft.trim().ifEmpty { "BookMriya" }
                    val updated = settings.copy(downloadFolder = chosen, folderChosen = true)
                    settings = updated
                    SettingsStore.save(context, updated)
                    folderSetupDismissed = true
                }) { Text(s.save) }
            },
            dismissButton = {
                TextButton(onClick = { folderSetupDismissed = true }) { Text(s.later) }
            }
        )
    }

    val sel = selected
    val scope = rememberCoroutineScope()
    val subs = remember(subsVersion) { SubscriptionsStore.load(context) }
    val isSubscribed = { url: String -> subs.any { it.url == url } }

    // Логін/пароль довільного каталогу: свої поля у власного,
    // інакше збережені окремо в AuthStore (для вбудованих).
    fun credsFor(cat: Catalog): Pair<String?, String?> {
        val u = cat.user.takeIf { it.isNotBlank() } ?: catalogAuth[cat.name]?.first
        val p = if (cat.user.isNotBlank()) cat.pass else catalogAuth[cat.name]?.second
        return u to p
    }

    // Глобальний пошук: питаємо всі каталоги паралельно. У кожного свій
    // шаблон пошуку (беремо з кореневої стрічки каталогу), тому спочатку
    // вантажимо корінь, потім — пошук за шаблоном.
    fun submitGlobalSearch() {
        val q = globalQuery.trim()
        if (q.isEmpty() || globalSearching) return
        globalSearching = true
        globalDone = false
        scope.launch {
            val catalogs = CATALOGS + custom
            val sections = withContext(Dispatchers.IO) {
                coroutineScope {
                    catalogs.map { cat ->
                        async {
                            try {
                                val client = Net.buildClient(settings)
                                val (u, p) = credsFor(cat)
                                val root = Net.fetchFirst(client, cat.urls, u, p)
                                val rootFeed = OpdsParser.parse(
                                    ByteArrayInputStream(root.bytes), root.url
                                )
                                val template = rootFeed.searchUrl ?: return@async null
                                val sr = Net.fetchFirst(
                                    client, listOf(searchUrlFor(template, q)), u, p
                                )
                                val sfeed = OpdsParser.parse(
                                    ByteArrayInputStream(sr.bytes), sr.url
                                )
                                GlobalSection(cat, template, sfeed.entries)
                            } catch (e: Exception) {
                                AppLog.d("Global search failed for ${cat.name}: ${e.message}")
                                GlobalSection(cat, null, emptyList(), e.message)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            globalSections = sections
            globalSearching = false
            globalDone = true
        }
    }

    // Для сторінки книги: якщо її відкрито з глобального пошуку —
    // шаблон пошуку і логін/пароль її каталогу, інакше — поточної стрічки.
    val detailTemplate = globalBookTemplate ?: feed?.searchUrl
    val detailUser = globalBookCatalog?.let { credsFor(it).first } ?: credUser
    val detailPass = globalBookCatalog?.let { credsFor(it).second } ?: credPass

    // Додому: список каталогів, як у старої програми.
    fun goHome() {
        history.clear()
        currentCatalog = null
        selected = null
        newIds = emptySet()
        query = ""
        searchExpanded = false
        globalQuery = ""
        globalSections = emptyList()
        globalDone = false
        globalBookCatalog = null
        globalBookTemplate = null
    }

    // Підписка: зберігаємо поточний список книг як базу, щоб новими
    // вважалися лише книги, що з'являться після підписки.
    fun toggleSubscription(type: String, name: String, url: String, user: String?, pass: String?) {
        if (isSubscribed(url)) {
            SubscriptionsStore.save(context, subs.filterNot { it.url == url })
            subsVersion++
            Toast.makeText(context, s.unsubscribed, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val baseline = try {
                withContext(Dispatchers.IO) {
                    val client = Net.buildClient(settings)
                    val r = Net.fetchFirst(client, listOf(url), user, pass)
                    OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url).entries.map { it.id }.toSet()
                }
            } catch (e: Exception) {
                emptySet()
            }
            SubscriptionsStore.save(
                context,
                SubscriptionsStore.load(context) + Subscription(
                    type = type, name = name, url = url,
                    knownIds = baseline, newIds = emptySet(),
                    lastCheck = System.currentTimeMillis(),
                    user = user ?: "",
                    pass = pass ?: ""
                )
            )
            subsVersion++
            Toast.makeText(context, s.subscribed, Toast.LENGTH_SHORT).show()
        }
    }

    val title = when {
        sel != null -> sel.title
        current == null -> s.appNameRunes
        else -> feed?.title ?: s.loading
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color(0xFFE53935),
                        fontSize = 22.sp,
                        letterSpacing = 2.sp
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (themeIsDark(settings.theme)) Color(0xFF121212) else Color.White
                ),
                navigationIcon = {
                    Box {
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
                                text = { Text(s.myBooks) },
                                onClick = { showMenu = false; showMyBooks = true }
                            )
                            DropdownMenuItem(
                                text = { Text(s.subscriptions) },
                                onClick = { showMenu = false; showSubscriptions = true }
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
                },
                actions = {
                    if (current != null || sel != null) {
                        IconButton(onClick = { goHome() }) {
                            Icon(Icons.Filled.Home, contentDescription = s.home)
                        }
                    }
                    if (sel == null && feed?.searchUrl != null) {
                        IconButton(onClick = { searchExpanded = !searchExpanded }) {
                            Icon(Icons.Filled.Search, contentDescription = s.search)
                        }
                    }
                    if (sel == null && feed != null) {
                        IconButton(onClick = {
                            val updated = settings.copy(feedGrid = !settings.feedGrid)
                            settings = updated
                            SettingsStore.save(context, updated)
                        }) {
                            Text(
                                if (settings.feedGrid) "☰" else "▦",
                                fontSize = 22.sp
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
                sel != null -> {
                val downloadedBooks = remember(sel) { Reader.downloadedMap(context, settings, s) }
                val readBook = remember(sel) { Reader.findDownloaded(sel, downloadedBooks) }
                BookDetail(
                    s = s,
                    e = sel,
                    onAuthor = { a ->
                        val target = authorTarget(a, sel, detailTemplate)
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
                    onOpenSite = { url ->
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message ?: "?", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToggleAuthor = { a ->
                        val target = authorTarget(a, sel, detailTemplate)
                        if (target != null) {
                            toggleSubscription("author", a.name, target, detailUser, detailPass)
                        } else {
                            Toast.makeText(context, s.authorUnavailable, Toast.LENGTH_SHORT).show()
                        }
                    },
                    isAuthorSubscribed = { a ->
                        val target = authorTarget(a, sel, detailTemplate)
                        target != null && isSubscribed(target)
                    },
                    onToggleSeries = {
                        val link = sel.seriesLink
                        if (link != null) {
                            toggleSubscription("series", sel.seriesName ?: sel.title, link.href, detailUser, detailPass)
                        }
                    },
                    isSeriesSubscribed = sel.seriesLink?.let { isSubscribed(it.href) } ?: false,
                    onDownload = { link -> Downloads.start(context, sel, link, s, detailUser, detailPass, settings.downloadFolder, settings, settings.safFolderUri) },
                    onRead = readBook?.let { rb -> { readingBook = rb } },
                    downloadedBooks = downloadedBooks,
                    onOpenExternal = { book -> MyBooks.open(context, book, s) },
                    onReadBook = { rb -> readingBook = rb }
                )
            }
                current == null -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = globalQuery,
                        onValueChange = {
                            globalQuery = it
                            if (it.isBlank()) {
                                globalSections = emptyList()
                                globalDone = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        singleLine = true,
                        placeholder = { Text(s.searchAll) },
                        trailingIcon = {
                            IconButton(onClick = { submitGlobalSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = s.search)
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitGlobalSearch() })
                    )
                    if (globalSearching) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (globalSections.isNotEmpty() || globalDone) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (globalSections.all { it.entries.isEmpty() }) {
                                item {
                                    Text(
                                        s.noSearchResults,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                            globalSections.forEach { section ->
                                if (section.entries.isNotEmpty()) {
                                    item {
                                        Text(
                                            section.catalog.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(
                                                16.dp, 12.dp, 16.dp, 4.dp
                                            )
                                        )
                                    }
                                    items(section.entries) { e ->
                                        EntryRow(
                                            s = s,
                                            e = e,
                                            isNew = false,
                                            onBook = {
                                                selected = e
                                                globalBookCatalog = section.catalog
                                                globalBookTemplate = section.template
                                            },
                                            onOpen = { target ->
                                                currentCatalog = section.catalog
                                                history.add(listOf(target))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        HomeList(
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
                    }
                }
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("${s.errorTitle}: $error")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reloadKey++ }) { Text(s.retry) }
                    if (error?.startsWith("HTTP 401") == true && currentCatalog != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showAuth = true }) { Text(s.enterLogin) }
                    }
                }
                f != null -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        f.icon?.let { iconUrl ->
                            AsyncImage(
                                model = iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            f.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                    }
                    if (searchExpanded) {
                        f.searchUrl?.let { template ->
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .focusRequester(searchFocus),
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
                    }
                    if (settings.feedGrid) {
                        FeedGrid(
                            s = s,
                            feed = f,
                            newIds = newIds,
                            onBook = { selected = it },
                            onOpen = { target -> history.add(listOf(target)) },
                            onNext = { next -> history.add(listOf(next)) }
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(f.entries) { e ->
                                EntryRow(
                                    s = s,
                                    e = e,
                                    isNew = newIds.contains(e.id),
                                    onBook = { selected = e },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookDetail(
    s: AppStrings,
    e: Entry,
    onAuthor: (Author) -> Unit,
    onSeries: (String) -> Unit,
    onOpenSite: (String) -> Unit,
    onToggleAuthor: (Author) -> Unit,
    isAuthorSubscribed: (Author) -> Boolean,
    onToggleSeries: () -> Unit,
    isSeriesSubscribed: Boolean,
    onDownload: (Link) -> Unit,
    onRead: (() -> Unit)? = null,
    downloadedBooks: Map<String, MyBook> = emptyMap(),
    onOpenExternal: (MyBook) -> Unit = {},
    onReadBook: (ReaderBook) -> Unit = {}
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
    var showCover by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf<Pair<Link, MyBook>?>(null) }
    val siteUrl = e.links.firstOrNull {
        it.rel == "alternate" && it.type.contains("html")
    }?.href

    if (showCover && cover != null) {
        Dialog(onDismissRequest = { showCover = false }) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showCover = false }
            )
        }
    }

    // Друге натискання на вже завантажену плашку: вибір, чим читати.
    val dlg = downloadDialog
    if (dlg != null) {
        val (link, book) = dlg
        AlertDialog(
            onDismissRequest = { downloadDialog = null },
            title = { Text(book.displayName, maxLines = 2) },
            text = {
                Column {
                    if (Reader.supports(book.fileName)) {
                        TextButton(onClick = {
                            downloadDialog = null
                            onReadBook(ReaderBook.fromMyBook(book))
                        }) { Text(s.read) }
                    }
                    TextButton(onClick = {
                        downloadDialog = null
                        onOpenExternal(book)
                    }) { Text(s.openWith) }
                    TextButton(onClick = {
                        downloadDialog = null
                        onDownload(link)
                    }) { Text(s.downloadAgain) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { downloadDialog = null }) { Text(s.cancel) }
            }
        )
    }

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
                    modifier = Modifier
                        .width(130.dp)
                        .heightIn(max = 200.dp)
                        .clickable { showCover = true }
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                e.authors.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onAuthor(a) }) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                        }
                        TextButton(onClick = { onToggleAuthor(a) }) {
                            Text(if (isAuthorSubscribed(a)) "✓" else "+")
                        }
                    }
                }
            }
        }

        if (e.categories.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                s.genre + ": " + e.categories.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val metaLine = listOfNotNull(
            e.language.takeIf { it.isNotBlank() }?.let { s.language + ": " + it },
            e.year?.let { s.year + ": " + it }
        ).joinToString("  •  ")
        if (metaLine.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(metaLine, style = MaterialTheme.typography.bodyMedium)
        }
        if (siteUrl != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenSite(siteUrl) }) {
                Text(s.openOnSite, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (downloads.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow {
                downloads.forEach { l ->
                    val book = downloadedBooks[Reader.expectedFileName(e, l)]
                    FormatChip(l.format, downloaded = book != null) {
                        if (book != null) downloadDialog = l to book
                        else onDownload(l)
                    }
                }
            }
        }

        if (onRead != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRead) { Text(s.read) }
        }

        val seriesLink = e.seriesLink
        val seriesName = e.seriesName
        if (seriesLink != null && seriesName != null) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSeries(seriesLink.href) }) {
                    Text(
                        s.series + ": " + seriesName +
                            (e.seriesNumber?.let { " #$it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                TextButton(onClick = onToggleSeries) {
                    Text(if (isSeriesSubscribed) "✓" else "+")
                }
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

/** Діалог логіна/пароля, коли каталог повернув HTTP 401. */
@Composable
private fun AuthDialog(
    s: AppStrings,
    catalogName: String,
    initialUser: String,
    initialPass: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.authRequiredTitle + ": " + catalogName) },
        text = {
            Column {
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
                Text(
                    s.authNote,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(user.trim(), pass) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

/** Адреса фавіконки каталогу (для логотипа в картці); null — показати монограму. */
private fun faviconUrl(c: Catalog): String? {
    val first = c.urls.firstOrNull() ?: return null
    return try {
        val u = java.net.URL(first)
        "${u.protocol}://${u.host}/favicon.ico"
    } catch (e: Exception) {
        null
    }
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "BookMriya",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE53935),
                    letterSpacing = 2.sp
                )
            }
        }
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
    Card(
        onClick = { onOpen(c) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    c.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                AsyncImage(
                    model = faviconUrl(c),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (c.user.isNotBlank()) {
                        Text("🔒", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Text(
                    c.noteFor(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (onEdit != null || onDelete != null) {
                    Row {
                        if (onEdit != null) {
                            TextButton(onClick = onEdit) { Text(s.edit) }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) { Text(s.delete) }
                        }
                    }
                }
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Кольорова плашка формату, як у Library: FB2 — червона, HTML — синя і т.д. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormatChip(format: String, downloaded: Boolean = false, onClick: () -> Unit) {
    val color = if (downloaded) Color(0xFF9E9E9E) else when (format.uppercase()) {
        "FB2" -> Color(0xFFE53935)
        "HTML" -> Color(0xFF1E88E5)
        "TXT" -> Color(0xFF43A047)
        "RTF" -> Color(0xFF9E9D24)
        "EPUB" -> Color(0xFF8E24AA)
        "MOBI" -> Color(0xFFD81B60)
        else -> Color(0xFF757575)
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier
            .height(32.dp)
            .padding(end = 6.dp, bottom = 6.dp)
    ) {
        Text("⬇ " + format.uppercase(), style = MaterialTheme.typography.labelMedium)
    }
}

/** Сітка обкладинок (2 колонки): книги — великими картками, навігаційні
 *  рядки і кнопка «Далі» — на всю ширину. */
@Composable
private fun FeedGrid(
    s: AppStrings,
    feed: Feed,
    newIds: Set<String>,
    onBook: (Entry) -> Unit,
    onOpen: (String) -> Unit,
    onNext: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        gridItems(
            feed.entries,
            key = { it.id },
            span = { e -> if (e.isBook) GridItemSpan(1) else GridItemSpan(2) }
        ) { e ->
            if (e.isBook) {
                BookGridCell(
                    s = s,
                    e = e,
                    isNew = newIds.contains(e.id),
                    onBook = { onBook(e) }
                )
            } else {
                EntryRow(
                    s = s,
                    e = e,
                    isNew = newIds.contains(e.id),
                    onBook = { onBook(e) },
                    onOpen = onOpen
                )
            }
        }
        feed.nextUrl?.let { next ->
            item(span = { GridItemSpan(2) }) {
                TextButton(
                    onClick = { onNext(next) },
                    modifier = Modifier.padding(8.dp)
                ) { Text(s.nextPage) }
            }
        }
    }
}

@Composable
private fun BookGridCell(
    s: AppStrings,
    e: Entry,
    isNew: Boolean,
    onBook: () -> Unit
) {
    val cover = e.links.firstOrNull {
        it.rel.contains("thumbnail") || it.rel.contains("image")
    }?.href
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onBook() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                e.title.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (isNew) {
                Text(
                    s.newBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        if (e.authors.isNotEmpty()) {
            Text(
                e.authors.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EntryRow(
    s: AppStrings,
    e: Entry,
    isNew: Boolean = false,
    onBook: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isNew) {
                    Text(
                        s.newBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (e.authors.isNotEmpty()) {
                Text(
                    e.authors.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!e.isBook && e.summary.isNotBlank()) {
                Text(
                    e.summary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    HorizontalDivider()
}