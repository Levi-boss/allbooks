package app.allbooks

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import app.allbooks.net.Net
import app.allbooks.net.NetSettings
import app.allbooks.net.ProxyMode
import app.allbooks.opds.Entry
import app.allbooks.opds.Link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class DownloadItem(
    val id: Long,
    val title: String,
    val fileName: String,
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val canRetry: Boolean = false,
    val safDocUri: String = ""
) {
    val progress: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    val percent: Int get() = (progress * 100).toInt()
    val isSuccessful: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
    val isActive: Boolean
        get() = status == DownloadManager.STATUS_PENDING ||
            status == DownloadManager.STATUS_RUNNING ||
            status == DownloadManager.STATUS_PAUSED
    fun statusText(s: AppStrings): String = when (status) {
        DownloadManager.STATUS_PENDING -> s.statusPending
        DownloadManager.STATUS_RUNNING -> s.statusRunning
        DownloadManager.STATUS_PAUSED -> s.statusPaused
        DownloadManager.STATUS_SUCCESSFUL -> s.statusSuccessful
        DownloadManager.STATUS_FAILED -> s.statusFailed
        else -> s.statusUnknown
    }
}

object Downloads {
    private const val PREFS = "allbooks_downloads"
    private const val KEY = "ids"
    // Метадані задачі для повтору: url, назва, ім'я файлу, папка або SAF-адреса,
    // логін, пароль, тип ("dm" або "saf") (\u0001 — роздільник).
    private const val META_PREFIX = "meta_"

    // Стан ручних завантажень (SAF-папка і OkHttp у загальну папку
    // Завантаження): "item_<id>" = назва|ім'я файлу|статус|завантажено|всього|адреса документа.
    private const val SAF_PREFS = "allbooks_saf_downloads"
    private const val SAF_ITEM_PREFIX = "item_"

    private val safScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun trackedIds(c: Context): List<Long> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { it.trim().toLongOrNull() }
    }

    private fun saveTrackedIds(c: Context, ids: List<Long>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, ids.joinToString("\n")).apply()
    }

    // Пароль каталогу (поле 5) зберігається зашифрованим (SecretBox);
    // старі незашифровані значення читаються як є.
    private fun saveMeta(c: Context, id: Long, parts: List<String>) {
        val safe = parts.toMutableList()
        if (safe.size > 5) safe[5] = SecretBox.encrypt(safe[5])
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(META_PREFIX + id, safe.joinToString("\u0001")).apply()
    }

    private fun readMeta(c: Context, id: Long): List<String>? {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(META_PREFIX + id, null) ?: return null
        val parts = raw.split('\u0001').toMutableList()
        if (parts.size > 5) parts[5] = SecretBox.decrypt(parts[5])
        return parts
    }

    private fun clearMeta(c: Context, id: Long) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(META_PREFIX + id).apply()
    }

    private fun safPrefs(c: Context) =
        c.getSharedPreferences(SAF_PREFS, Context.MODE_PRIVATE)

    private fun saveSafItem(c: Context, item: DownloadItem) {
        val raw = listOf(
            item.title, item.fileName, item.status.toString(),
            item.downloaded.toString(), item.total.toString(), item.safDocUri
        ).joinToString("\u0001")
        safPrefs(c).edit().putString(SAF_ITEM_PREFIX + item.id, raw).apply()
    }

    private fun readSafItem(c: Context, id: Long): DownloadItem? {
        val raw = safPrefs(c).getString(SAF_ITEM_PREFIX + id, null) ?: return null
        val p = raw.split('\u0001')
        if (p.size < 5) return null
        return DownloadItem(
            id = id,
            title = p[0],
            fileName = p[1],
            status = p[2].toIntOrNull() ?: DownloadManager.STATUS_FAILED,
            downloaded = p[3].toLongOrNull() ?: 0L,
            total = p[4].toLongOrNull() ?: -1L,
            canRetry = readMeta(c, id) != null,
            safDocUri = p.getOrNull(5) ?: ""
        )
    }

    private fun clearSafItem(c: Context, id: Long) {
        safPrefs(c).edit().remove(SAF_ITEM_PREFIX + id).apply()
    }

    private fun listSafItems(c: Context): List<DownloadItem> {
        return safPrefs(c).all.keys
            .mapNotNull { it.removePrefix(SAF_ITEM_PREFIX).toLongOrNull() }
            .mapNotNull { readSafItem(c, it) }
    }

    // --- Сповіщення про прогрес (для SAF-завантажень; у DownloadManager
    // своє сповіщення вже є). Канал — низької важливості, без звуку.
    private const val CHANNEL_ID = "bookmriya_downloads"

    private fun notificationId(id: Long): Int = (id and 0x7fffffff).toInt()

    private fun ensureChannel(c: Context, s: AppStrings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, s.downloads, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showSafNotification(c: Context, s: AppStrings, item: DownloadItem) {
        ensureChannel(c, s)
        val builder = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.title)
            .setOngoing(item.isActive)
            .setAutoCancel(!item.isActive)
            .setOnlyAlertOnce(true)
        when {
            item.isSuccessful -> {
                builder.setContentText("${s.statusSuccessful}: ${item.fileName}")
                    .setProgress(0, 0, false)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
            }
            item.isFailed -> {
                builder.setContentText("${s.statusFailed}: ${item.fileName}")
                    .setProgress(0, 0, false)
            }
            item.total > 0 -> {
                builder.setContentText("${s.downloading}: ${item.percent}%")
                    .setProgress(100, item.percent, false)
            }
            else -> {
                builder.setContentText(s.downloading)
                    .setProgress(0, 0, true)
            }
        }
        NotificationManagerCompat.from(c).notify(notificationId(item.id), builder.build())
    }

    private fun cancelSafNotification(c: Context, id: Long) {
        NotificationManagerCompat.from(c).cancel(notificationId(id))
    }

    fun extension(link: Link): String {
        val t = link.type.lowercase()
        return if (t.contains("fb2") && t.contains("zip")) "fb2.zip" else link.format
    }

    /** Формат для пакетного завантаження: fb2, потім fb2.zip, epub, txt, інше. */
    fun preferredLink(entry: Entry): Link? {
        val links = entry.links.filter { it.isAcquisition }
        if (links.isEmpty()) return null
        fun rank(l: Link): Int {
            val t = l.type.lowercase()
            return when {
                t.contains("fb2") && !t.contains("zip") -> 0
                t.contains("fb2") -> 1
                t.contains("epub") -> 2
                t.contains("txt") -> 3
                else -> 4
            }
        }
        return links.minByOrNull { rank(it) }
    }

    /** Почати одне завантаження. Повертає false, якщо не вдалося стати в чергу.
     *  quiet = true — без сповіщень-тостів про кожен файл (для пакетного завантаження). */
    fun start(
        context: Context,
        entry: Entry,
        link: Link,
        s: AppStrings,
        user: String? = null,
        pass: String? = null,
        folder: String = "BookMriya",
        settings: NetSettings = NetSettings(),
        safFolderUri: String = "",
        quiet: Boolean = false
    ): Boolean {
        val safe = entry.title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .trim()
            .take(80)
            .ifEmpty { "book" }
        val name = "$safe.${extension(link)}"
        val via = when {
            safFolderUri.isNotBlank() -> "saf"
            settings.proxyMode != ProxyMode.NONE || settings.useDoh -> "okhttp"
            else -> "dm"
        }
        AppLog.d("Download start: $name (${link.format}) via $via")
        return if (safFolderUri.isNotBlank()) {
            startSaf(context, link.href, entry.title, name, safFolderUri, user, pass, settings, s, quiet)
        } else {
            enqueue(context, link.href, entry.title, name, folder, user, pass, s, settings, quiet)
        }
    }

    /** Повторна спроба невдалого завантаження за збереженими метаданими. */
    fun retry(context: Context, id: Long, s: AppStrings) {
        val meta = readMeta(context, id)
        if (meta == null || meta.size < 4) {
            Toast.makeText(context, s.openFailed, Toast.LENGTH_SHORT).show()
            return
        }
        val url = meta[0]
        val title = meta[1]
        val name = meta[2]
        val folder = meta[3]
        val user = meta.getOrNull(4)?.ifEmpty { null }
        val pass = meta.getOrNull(5)?.ifEmpty { null }
        val kind = meta.getOrNull(6) ?: "dm"
        val settings = SettingsStore.load(context)
        remove(context, id)
        if (kind == "saf") {
            startSaf(context, url, title, name, folder, user, pass, settings, s)
        } else if (kind == "okhttp") {
            startOkHttp(context, url, title, name, folder, user, pass, settings, s)
        } else {
            enqueue(context, url, title, name, folder, user, pass, s, settings)
        }
    }

    /** Завислі записи ручних завантажень (SAF і OkHttp): процес загинув,
     *  поки завантаження висіло зі статусом RUNNING/PENDING. Переводимо
     *  їх у FAILED, щоб користувач міг повторити їх зі списку (метадані
     *  збережені, canRetry = true). Викликається при запуску застосунку. */
    fun failStale(context: Context) {
        val appContext = context.applicationContext
        listSafItems(appContext)
            .filter {
                it.status == DownloadManager.STATUS_RUNNING ||
                    it.status == DownloadManager.STATUS_PENDING
            }
            .forEach { item ->
                saveSafItem(appContext, item.copy(status = DownloadManager.STATUS_FAILED))
                cancelSafNotification(appContext, item.id)
            }
    }

    private fun enqueue(context: Context, url: String, title: String, name: String, folder: String, user: String?, pass: String?, s: AppStrings, settings: NetSettings = NetSettings(), quiet: Boolean = false): Boolean {
        val cleanFolder = folder
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .trim()
            .ifEmpty { "BookMriya" }
        // Системний DownloadManager не знає про проксі та DoH застосунку:
        // з ними качаємо самі через OkHttp і пишемо в загальну папку Завантаження.
        if (settings.proxyMode != ProxyMode.NONE || settings.useDoh) {
            return startOkHttp(context, url, title, name, cleanFolder, user, pass, settings, s, quiet)
        }
        return try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(title)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$cleanFolder/$name"
                )
                .addRequestHeader("User-Agent", "BookMriya/2.0 (Android)")
            if (!user.isNullOrBlank()) {
                req.addRequestHeader("Authorization", Credentials.basic(user, pass ?: ""))
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            saveTrackedIds(context, trackedIds(context) + id)
            saveMeta(
                context, id,
                listOf(url, title, name, cleanFolder, user ?: "", pass ?: "", "dm")
            )
            if (!quiet) Toast.makeText(context, "${s.downloading}: $name", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            AppLog.d("Download failed (dm): $name: ${e.message}")
            if (!quiet) Toast.makeText(context, "${s.errorTitle}: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Завантаження у загальну папку Завантаження через OkHttp (клієнт
     * застосунку — з проксі та DoH). Android 10+: пишемо в
     * MediaStore.Downloads; старіше — звичайним файлом у публічну
     * папку Завантаження. Стан зберігається, як у SAF-завантажень,
     * тому запис видно в списку, його можна відкрити, повторити і видалити.
     */
    private fun startOkHttp(
        context: Context,
        url: String,
        title: String,
        name: String,
        folder: String,
        user: String?,
        pass: String?,
        settings: NetSettings,
        s: AppStrings,
        quiet: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext
        var id = System.currentTimeMillis()
        while (readSafItem(appContext, id) != null) id++
        val item = DownloadItem(
            id = id, title = title, fileName = name,
            status = DownloadManager.STATUS_RUNNING,
            downloaded = 0L, total = -1L, canRetry = true
        )
        saveSafItem(appContext, item)
        saveMeta(
            appContext, id,
            listOf(url, title, name, folder, user ?: "", pass ?: "", "okhttp")
        )
        showSafNotification(appContext, s, item)
        if (!quiet) Toast.makeText(context, "${s.downloading}: $name", Toast.LENGTH_SHORT).show()
        safScope.launch {
            var downloaded = 0L
            var total = -1L
            var docUri: Uri? = null
            var targetFile: File? = null
            var lastSave = 0L
            fun saveProgress(status: Int) {
                val cur = item.copy(
                    status = status, downloaded = downloaded, total = total,
                    safDocUri = docUri?.toString() ?: ""
                )
                saveSafItem(appContext, cur)
                showSafNotification(appContext, s, cur)
            }
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BookMriya/2.0 (Android)")
                if (!user.isNullOrBlank()) {
                    reqBuilder.header("Authorization", Credentials.basic(user, pass ?: ""))
                }
                val client = Net.buildClient(settings)
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    total = body.contentLength()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folder")
                        }
                        docUri = appContext.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        ) ?: throw IOException("MediaStore insert returned null")
                        appContext.contentResolver.openOutputStream(docUri!!)?.use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastSave > 500) {
                                        lastSave = now
                                        saveProgress(DownloadManager.STATUS_RUNNING)
                                    }
                                }
                                out.flush()
                            }
                        } ?: throw IOException("cannot open output stream")
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ), folder
                        )
                        if (!dir.exists()) dir.mkdirs()
                        targetFile = File(dir, name)
                        FileOutputStream(targetFile!!).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastSave > 500) {
                                        lastSave = now
                                        saveProgress(DownloadManager.STATUS_RUNNING)
                                    }
                                }
                                out.flush()
                            }
                        }
                        docUri = FileProvider.getUriForFile(
                            appContext, "app.allbooks.fileprovider", targetFile!!
                        )
                    }
                }
                saveProgress(DownloadManager.STATUS_SUCCESSFUL)
            } catch (e: Exception) {
                AppLog.d("Download failed (okhttp): $name: ${e.message}")
                if (docUri != null) {
                    try {
                        appContext.contentResolver.delete(docUri!!, null, null)
                    } catch (ignored: Exception) {
                    }
                }
                targetFile?.delete()
                saveProgress(DownloadManager.STATUS_FAILED)
            }
        }
        true
    }

    /**
     * Завантаження у папку, вибрану через файловий менеджер (SAF).
     * DownloadManager туди писати не вміє, тому качаємо самі через OkHttp
     * і пишемо через ContentResolver; прогрес видно в списку завантажень.
     */
    private fun startSaf(
        context: Context,
        url: String,
        title: String,
        name: String,
        folderUri: String,
        user: String?,
        pass: String?,
        settings: NetSettings,
        s: AppStrings,
        quiet: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext
        var id = System.currentTimeMillis()
        while (readSafItem(appContext, id) != null) id++
        val item = DownloadItem(
            id = id, title = title, fileName = name,
            status = DownloadManager.STATUS_RUNNING,
            downloaded = 0L, total = -1L, canRetry = true
        )
        saveSafItem(appContext, item)
        saveMeta(
            appContext, id,
            listOf(url, title, name, folderUri, user ?: "", pass ?: "", "saf")
        )
        showSafNotification(appContext, s, item)
        if (!quiet) Toast.makeText(context, "${s.downloading}: $name", Toast.LENGTH_SHORT).show()
        safScope.launch {
            var downloaded = 0L
            var total = -1L
            var docUri: Uri? = null
            var lastSave = 0L
            fun saveProgress(status: Int) {
                val cur = item.copy(
                    status = status, downloaded = downloaded, total = total,
                    safDocUri = docUri?.toString() ?: ""
                )
                saveSafItem(appContext, cur)
                showSafNotification(appContext, s, cur)
            }
            try {
                val treeUri = Uri.parse(folderUri)
                val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                docUri = DocumentsContract.createDocument(
                    appContext.contentResolver, treeDoc,
                    "application/octet-stream", name
                ) ?: throw IOException("createDocument returned null")
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BookMriya/2.0 (Android)")
                if (!user.isNullOrBlank()) {
                    reqBuilder.header("Authorization", Credentials.basic(user, pass ?: ""))
                }
                val client = Net.buildClient(settings)
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    total = body.contentLength()
                    saveProgress(DownloadManager.STATUS_RUNNING)
                    appContext.contentResolver.openOutputStream(docUri)?.use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastSave > 500) {
                                    lastSave = now
                                    saveProgress(DownloadManager.STATUS_RUNNING)
                                }
                            }
                            out.flush()
                        }
                    } ?: throw IOException("cannot open output stream")
                }
                saveProgress(DownloadManager.STATUS_SUCCESSFUL)
            } catch (e: Exception) {
                AppLog.d("Download failed (saf): $name: ${e.message}")
                if (docUri != null) {
                    try {
                        DocumentsContract.deleteDocument(appContext.contentResolver, docUri)
                    } catch (ignored: Exception) {
                    }
                }
                saveProgress(DownloadManager.STATUS_FAILED)
            }
        }
        true
    }

    fun list(context: Context, s: AppStrings): List<DownloadItem> {
        val out = mutableListOf<DownloadItem>()
        out.addAll(listSafItems(context))
        val ids = trackedIds(context)
        if (ids.isNotEmpty()) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(*ids.toLongArray())
            val cursor: Cursor = try {
                dm.query(query)
            } catch (e: Exception) {
                return out.sortedByDescending { it.id }
            }
            cursor.use {
                val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val downloadedCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriCol = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (it.moveToNext()) {
                    val id = if (idCol >= 0) it.getLong(idCol) else continue
                    val title = if (titleCol >= 0) it.getString(titleCol) ?: s.book else s.book
                    val status = if (statusCol >= 0) it.getInt(statusCol) else DownloadManager.STATUS_FAILED
                    val downloaded = if (downloadedCol >= 0) it.getLong(downloadedCol) else 0L
                    val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                    val uri = if (uriCol >= 0) it.getString(uriCol) ?: "" else ""
                    val fileName = uri.substringAfterLast('/').ifBlank { title }
                    out.add(DownloadItem(id, title, fileName, status, downloaded, total, readMeta(context, id) != null))
                }
            }
        }
        // Старіші — внизу, нові — вгорі (за id, він росте)
        return out.sortedByDescending { it.id }
    }

    fun remove(context: Context, id: Long) {
        val safItem = readSafItem(context, id)
        if (safItem != null) {
            if (safItem.safDocUri.isNotBlank()) {
                try {
                    val uri = Uri.parse(safItem.safDocUri)
                    when (uri.authority) {
                        // Файл з MediaStore (OkHttp-завантаження, Android 10+).
                        "media" -> context.contentResolver.delete(uri, null, null)
                        // Звичайний файл у папці Завантаження (OkHttp, старіше
                        // Android 10): FileProvider не видаляє, стираємо файл
                        // напряму за метаданими (папка/ім'я).
                        "app.allbooks.fileprovider" -> {
                            val meta = readMeta(context, id)
                            if (meta != null && meta.size >= 4) {
                                File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_DOWNLOADS
                                    ), "${meta[3]}/${meta[2]}"
                                ).delete()
                            }
                        }
                        else -> DocumentsContract.deleteDocument(
                            context.contentResolver, uri
                        )
                    }
                } catch (e: Exception) {
                    // ігноруємо — головне прибрати зі списку
                }
            }
            clearSafItem(context, id)
            clearMeta(context, id)
            cancelSafNotification(context, id)
            return
        }
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
            // ігноруємо — головне прибрати зі списку
        }
        saveTrackedIds(context, trackedIds(context) - id)
        clearMeta(context, id)
    }

    fun open(context: Context, id: Long, s: AppStrings) {
        val safItem = readSafItem(context, id)
        if (safItem != null) {
            if (safItem.safDocUri.isBlank()) {
                Toast.makeText(context, s.fileNotReady, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(safItem.safDocUri), "application/octet-stream")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, s.openBook)
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "${s.openFailed}: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) {
                Toast.makeText(context, s.fileNotReady, Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Дати системі вибрати читалку; якщо типу не впізнає — відкрити як файл
            val chooser = Intent.createChooser(intent, s.openBook)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "${s.openFailed}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Назва папки з адреси SAF-дерева (напр. "primary:Download/Книги" → "Книги"). */
    fun safDisplayName(treeUri: String): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            id.substringAfterLast(':').substringAfterLast('/').ifBlank { treeUri }
        } catch (e: Exception) {
            treeUri
        }
    }
}
