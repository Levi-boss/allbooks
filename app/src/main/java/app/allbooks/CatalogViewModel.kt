package app.allbooks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.allbooks.net.Net
import app.allbooks.net.NetSettings
import app.allbooks.opds.Entry
import app.allbooks.opds.Feed
import app.allbooks.opds.Link
import app.allbooks.opds.OpdsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Стан стрічки каталогу: завантаження, заголовок, книги, помилка, «назад». */
data class CatalogUiState(
    val loading: Boolean = false,
    val title: String? = null,
    val entries: List<Entry> = emptyList(),
    val error: String? = null,
    val canGoBack: Boolean = false,
    val isHome: Boolean = true,
    val feed: Feed? = null
)

/** Стан глобального пошуку (одне поле по всіх каталогах). */
data class GlobalSearchState(
    val query: String = "",
    val searching: Boolean = false,
    val sections: List<GlobalSection> = emptyList(),
    val done: Boolean = false
)

/** Ліміт пакетного завантаження: більше — тільки після підтвердження. */
const val BATCH_CAP = 30

/** Стан пакетного завантаження: скільки стало в чергу з усього списку. */
data class BatchState(
    val running: Boolean = false,
    val queued: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val runId: Long = 0L
)

/** Один крок навігації стрічкою: адреси для спроби + каталог, звідки прийшли. */
private data class NavEntry(val urls: List<String>, val catalog: Catalog?)

/**
 * Стан перегляду каталогів: стек навігації, завантаження стрічки,
 * глобальний пошук, вибрана книга, підписки, власні каталоги.
 * Живе довше за екран: поворот телефона стрічку не губить.
 * Налаштування, завантаження і читалка тут не чіпаються.
 */
class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext

    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val _currentCatalog = MutableStateFlow<Catalog?>(null)
    val currentCatalog: StateFlow<Catalog?> = _currentCatalog.asStateFlow()

    private val _customCatalogs = MutableStateFlow(CustomStore.load(context))
    val customCatalogs: StateFlow<List<Catalog>> = _customCatalogs.asStateFlow()

    private val _selectedBook = MutableStateFlow<Entry?>(null)
    val selectedBook: StateFlow<Entry?> = _selectedBook.asStateFlow()

    private val _selectedBookCatalog = MutableStateFlow<Catalog?>(null)
    val selectedBookCatalog: StateFlow<Catalog?> = _selectedBookCatalog.asStateFlow()

    private val _selectedBookTemplate = MutableStateFlow<String?>(null)
    val selectedBookTemplate: StateFlow<String?> = _selectedBookTemplate.asStateFlow()

    private val _globalSearch = MutableStateFlow(GlobalSearchState())
    val globalSearch: StateFlow<GlobalSearchState> = _globalSearch.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _newIds = MutableStateFlow<Set<String>>(emptySet())
    val newIds: StateFlow<Set<String>> = _newIds.asStateFlow()

    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    private val _batchState = MutableStateFlow(BatchState())
    val batchState: StateFlow<BatchState> = _batchState.asStateFlow()

    private var navStack = listOf<NavEntry>()
    private var loadJob: Job? = null
    private var batchJob: Job? = null

    init {
        refreshSubscriptions()
        // Стартова сторінка: відкрити вибраний каталог одразу при запуску.
        val name = SettingsStore.load(context).startCatalog
        if (name.isNotBlank()) {
            (CATALOGS + _customCatalogs.value).firstOrNull { it.name == name }?.let { openCatalog(it) }
        }
    }

    fun refreshSubscriptions() {
        _subscriptions.value = SubscriptionsStore.load(context)
    }

    /** Логін/пароль каталогу: свої поля у власного, інакше збережені окремо в AuthStore. */
    fun credsFor(cat: Catalog): Pair<String?, String?> {
        val auth = AuthStore.load(context)
        val u = cat.user.takeIf { it.isNotBlank() } ?: auth[cat.name]?.first
        val p = if (cat.user.isNotBlank()) cat.pass else auth[cat.name]?.second
        return u to p
    }

    /** Логін/пароль для сторінки вибраної книги: її каталог або поточний. */
    fun detailCreds(): Pair<String?, String?> {
        val cat = _selectedBookCatalog.value ?: _currentCatalog.value ?: return null to null
        return credsFor(cat)
    }

    fun openCatalog(c: Catalog) {
        _currentCatalog.value = c
        push(NavEntry(c.urls, c))
    }

    /** Відкрити адресу в стрічці; catalog — якщо переходимо в інший каталог. */
    fun openUrl(url: String, catalog: Catalog? = null) {
        if (catalog != null) _currentCatalog.value = catalog
        push(NavEntry(listOf(url), catalog ?: _currentCatalog.value))
    }

    /** Відкрити підписку: нові книги показати, мітки «НОВЕ» скинути. */
    fun openSubscription(url: String, ids: Set<String>) {
        _newIds.value = ids
        val cleared = SubscriptionsStore.load(context).map {
            if (it.url == url) it.copy(newIds = emptySet()) else it
        }
        SubscriptionsStore.save(context, cleared)
        refreshSubscriptions()
        openUrl(url)
    }

    fun goHome() {
        loadJob?.cancel()
        navStack = emptyList()
        _currentCatalog.value = null
        _selectedBook.value = null
        _selectedBookCatalog.value = null
        _selectedBookTemplate.value = null
        _newIds.value = emptySet()
        clearGlobalSearch()
        _uiState.value = CatalogUiState()
    }

    /** Назад: спочатку закрити книгу, потім — крок стрічки. True — обробили. */
    fun onBack(): Boolean {
        if (_selectedBook.value != null) {
            closeBook()
            return true
        }
        if (navStack.isEmpty()) return false
        navStack = navStack.dropLast(1)
        if (navStack.isEmpty()) {
            _currentCatalog.value = null
            _newIds.value = emptySet()
        }
        loadTop()
        return true
    }

    fun reload() = loadTop()

    /**
     * Пакетне завантаження: ставить у чергу список (книга + формат) через
     * існуючий Downloads.start, без тостів про кожен файл. Помилка одного
     * файлу чергу не валить. Скасування — через cancelBatchDownload():
     * нові завантаження після нього не стартують.
     */
    fun startBatchDownload(
        items: List<Pair<Entry, Link>>,
        skipped: Int,
        user: String?,
        pass: String?,
        folder: String,
        settings: NetSettings,
        safFolderUri: String,
        s: AppStrings
    ) {
        if (_batchState.value.running) return
        val runId = System.currentTimeMillis()
        batchJob = viewModelScope.launch {
            _batchState.value = BatchState(
                running = true, total = items.size, skipped = skipped, runId = runId
            )
            var queued = 0
            var failed = 0
            for ((entry, link) in items) {
                if (!isActive) break
                try {
                    if (Downloads.start(
                            context, entry, link, s, user, pass,
                            folder, settings, safFolderUri, quiet = true
                        )
                    ) queued++ else failed++
                } catch (e: Exception) {
                    AppLog.d("Batch download failed: ${entry.title}: ${e.message}")
                    failed++
                }
                _batchState.update { it.copy(queued = queued, failed = failed) }
            }
            _batchState.update { it.copy(running = false, queued = queued, failed = failed) }
        }
    }

    /** Скасувати пакетне завантаження: нові файли в чергу не підуть. */
    fun cancelBatchDownload() {
        batchJob?.cancel()
    }

    fun selectBook(e: Entry, catalog: Catalog? = null, template: String? = null) {
        _selectedBook.value = e
        _selectedBookCatalog.value = catalog
        _selectedBookTemplate.value = template
    }

    fun closeBook() {
        _selectedBook.value = null
        _selectedBookCatalog.value = null
        _selectedBookTemplate.value = null
    }

    fun setGlobalQuery(q: String) {
        _globalSearch.update {
            if (q.isBlank()) GlobalSearchState() else it.copy(query = q)
        }
    }

    fun clearGlobalSearch() {
        _globalSearch.value = GlobalSearchState()
    }

    // Глобальний пошук: питаємо всі каталоги паралельно. У кожного свій
    // шаблон пошуку (беремо з кореневої стрічки каталогу), тому спочатку
    // вантажимо корінь, потім — пошук за шаблоном.
    fun submitGlobalSearch() {
        val q = _globalSearch.value.query.trim()
        if (q.isEmpty() || _globalSearch.value.searching) return
        _globalSearch.update { it.copy(searching = true, done = false) }
        viewModelScope.launch {
            val settings = SettingsStore.load(context)
            val catalogs = CATALOGS + _customCatalogs.value
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
            _globalSearch.update { it.copy(searching = false, sections = sections, done = true) }
        }
    }

    /** Зберегти логін/пароль після 401 і перезавантажити стрічку. */
    fun saveAuth(user: String, pass: String) {
        val cat = _currentCatalog.value ?: return
        val custom = _customCatalogs.value
        if (custom.any { it.name == cat.name && it.urls == cat.urls }) {
            val updated = cat.copy(user = user, pass = pass)
            val next = custom.map { if (it.name == cat.name && it.urls == cat.urls) updated else it }
            _customCatalogs.value = next
            CustomStore.save(context, next)
            _currentCatalog.value = updated
        } else {
            val auth = AuthStore.load(context) + (cat.name to (user to pass))
            AuthStore.save(context, auth)
        }
        AppLog.d("Auth saved for ${cat.name}")
        _authRequired.value = false
        loadTop()
    }

    fun dismissAuth() {
        _authRequired.value = false
    }

    /** Показати діалог логіна/пароля знову (кнопка на екрані помилки 401). */
    fun requestAuth() {
        _authRequired.value = true
    }

    /** Додати або відредагувати власний каталог (з діалогу). Null — адреса закоротка. */
    fun saveCustomCatalog(name: String, url: String, user: String, pass: String, old: Catalog?): Catalog? {
        var u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("http", ignoreCase = true)) u = "https://$u"
        val n = name.replace("|", " ").replace("\n", " ").trim().ifEmpty { u }
        if (u.length <= 8) return null
        val cat = Catalog(n, u, listOf(u), user.trim(), pass)
        val custom = _customCatalogs.value
        val next = if (old != null) custom.map { if (it == old) cat else it } else custom + cat
        _customCatalogs.value = next
        CustomStore.save(context, next)
        if (_currentCatalog.value == old) _currentCatalog.value = cat
        return cat
    }

    fun deleteCustomCatalog(c: Catalog) {
        val next = _customCatalogs.value - c
        _customCatalogs.value = next
        CustomStore.save(context, next)
    }

    private fun push(entry: NavEntry) {
        _selectedBook.value = null
        _selectedBookCatalog.value = null
        _selectedBookTemplate.value = null
        navStack = navStack + entry
        loadTop()
    }

    private fun loadTop() {
        loadJob?.cancel()
        val top = navStack.lastOrNull()
        if (top == null) {
            _uiState.value = CatalogUiState()
            return
        }
        val catalog = _currentCatalog.value
        val (user, pass) = catalog?.let { credsFor(it) } ?: (null to null)
        loadJob = viewModelScope.launch {
            _uiState.value = CatalogUiState(loading = true, canGoBack = true, isHome = false)
            try {
                val settings = SettingsStore.load(context)
                val feed = withContext(Dispatchers.IO) {
                    val client = Net.buildClient(settings)
                    val r = Net.fetchFirst(client, top.urls, user, pass)
                    OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
                }
                _uiState.value = CatalogUiState(
                    loading = false,
                    title = feed.title,
                    entries = feed.entries,
                    error = null,
                    canGoBack = true,
                    isHome = false,
                    feed = feed
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("Feed error: ${e.message}")
                _uiState.value = CatalogUiState(
                    loading = false,
                    title = null,
                    entries = emptyList(),
                    error = e.message ?: e.javaClass.simpleName,
                    canGoBack = true,
                    isHome = false,
                    feed = null
                )
                if (e.message?.startsWith("HTTP 401") == true) _authRequired.value = true
            }
        }
    }
}
