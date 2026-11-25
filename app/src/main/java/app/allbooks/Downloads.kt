package app.allbooks

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import app.allbooks.opds.Entry
import app.allbooks.opds.Link
import okhttp3.Credentials

data class DownloadItem(
    val id: Long,
    val title: String,
    val fileName: String,
    val status: Int,
    val downloaded: Long,
    val total: Long
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

    private fun trackedIds(c: Context): List<Long> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { it.trim().toLongOrNull() }
    }

    private fun saveTrackedIds(c: Context, ids: List<Long>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, ids.joinToString("\n")).apply()
    }

    fun extension(link: Link): String {
        val t = link.type.lowercase()
        return if (t.contains("fb2") && t.contains("zip")) "fb2.zip" else link.format
    }

    fun start(context: Context, entry: Entry, link: Link, s: AppStrings, user: String? = null, pass: String? = null) {
        val safe = entry.title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .trim()
            .take(80)
            .ifEmpty { "book" }
        val name = "$safe.${extension(link)}"
        try {
            val req = DownloadManager.Request(Uri.parse(link.href))
                .setTitle(entry.title)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "BookMriya/$name"
                )
                .addRequestHeader("User-Agent", "BookMriya/2.0 (Android)")
            if (!user.isNullOrBlank()) {
                req.addRequestHeader("Authorization", Credentials.basic(user, pass ?: ""))
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            saveTrackedIds(context, trackedIds(context) + id)
            Toast.makeText(context, "${s.downloading}: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "${s.errorTitle}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun list(context: Context, s: AppStrings): List<DownloadItem> {
        val ids = trackedIds(context)
        if (ids.isEmpty()) return emptyList()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(*ids.toLongArray())
        val cursor: Cursor = try {
            dm.query(query)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<DownloadItem>()
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
                out.add(DownloadItem(id, title, fileName, status, downloaded, total))
            }
        }
        // Старіші — внизу, нові — вгорі (за id, він росте)
        return out.sortedByDescending { it.id }
    }

    fun remove(context: Context, id: Long) {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(id)
        } catch (e: Exception) {
            // ігноруємо — головне прибрати зі списку
        }
        saveTrackedIds(context, trackedIds(context) - id)
    }

    fun open(context: Context, id: Long, s: AppStrings) {
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
}
