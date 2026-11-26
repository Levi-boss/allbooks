package app.allbooks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import app.allbooks.net.NetSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Завантажена книга з папки завантажень.
 * Джерело — одне з трьох: файл на диску, документ SAF або запис DownloadManager.
 */
data class MyBook(
    val displayName: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val file: File? = null,
    val safUri: Uri? = null,
    val dmId: Long? = null,
    val sortKey: Long = modified
)

object MyBooks {
    private const val PROVIDER_AUTHORITY = "app.allbooks.fileprovider"

    private val BOOK_EXTS = setOf(
        "fb2", "epub", "mobi", "pdf", "txt", "rtf", "djvu", "html", "htm"
    )

    fun isBookFile(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".fb2.zip")) return true
        val ext = lower.substringAfterLast('.', "")
        return ext in BOOK_EXTS
    }

    /** Назва без розширення (для "книга.fb2.zip" — без ".fb2.zip"). */
    fun displayName(fileName: String): String {
        val lower = fileName.lowercase()
        val base = if (lower.endsWith(".fb2.zip")) fileName.dropLast(8)
        else fileName.substringBeforeLast('.', fileName)
        return base.ifBlank { fileName }
    }

    fun mimeFor(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "application/zip"
            lower.endsWith(".fb2") -> "application/x-fictionbook+xml"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".mobi") -> "application/x-mobipocket-ebook"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".rtf") -> "application/rtf"
            lower.endsWith(".djvu") -> "image/vnd.djvu"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            else -> "application/octet-stream"
        }
    }

    fun formatSize(size: Long, s: AppStrings): String {
        if (size <= 0) return ""
        return if (size < 1024 * 1024) {
            "${size / 1024} ${s.kb}"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", size / 1048576.0, s.mb)
        }
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(millis))
    }

    /**
     * Список книг із папки завантажень: SAF-папка, якщо вибрана через
     * файловий менеджер, інакше Завантаження/<папка>. Запасний варіант —
     * успішні завантаження з DownloadManager (на нових Android прямий
     * список папки може бути порожнім через scoped storage).
     */
    fun list(context: Context, settings: NetSettings, s: AppStrings): List<MyBook> {
        val out = mutableListOf<MyBook>()
        val seen = mutableSetOf<String>()
        if (settings.safFolderUri.isNotBlank()) {
            out.addAll(listSaf(context, settings.safFolderUri))
            out.forEach { seen.add(it.fileName) }
        } else {
            val folder = settings.downloadFolder.trim().ifEmpty { "BookMriya" }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                folder
            )
            val files = try {
                dir.listFiles()?.filter { it.isFile && isBookFile(it.name) } ?: emptyList()
            } catch (e: Exception) {
                AppLog.d("MyBooks dir list failed: ${e.message}")
                emptyList()
            }
            files.forEach { f ->
                out.add(
                    MyBook(
                        displayName(f.name), f.name, f.length(), f.lastModified(),
                        file = f, sortKey = f.lastModified()
                    )
                )
                seen.add(f.name)
            }
        }
        Downloads.list(context, s).filter { it.isSuccessful }.forEach { item ->
            if (item.fileName in seen) return@forEach
            if (!isBookFile(item.fileName)) return@forEach
            // У режимі SAF-папки беремо тільки SAF-завантаження
            // (звичайні лежать в іншій папці).
            if (settings.safFolderUri.isNotBlank() && item.safDocUri.isBlank()) return@forEach
            out.add(
                MyBook(
                    displayName(item.fileName), item.fileName,
                    item.total.coerceAtLeast(0), 0L,
                    dmId = item.id, sortKey = item.id
                )
            )
            seen.add(item.fileName)
        }
        return out.sortedByDescending { it.sortKey }
    }

    private fun listSaf(context: Context, treeUriString: String): List<MyBook> {
        val out = mutableListOf<MyBook>()
        try {
            val treeUri = Uri.parse(treeUriString)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            ) ?: return out
            cursor.use {
                val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (it.moveToNext()) {
                    val docId = if (idCol >= 0) it.getString(idCol) ?: continue else continue
                    val name = if (nameCol >= 0) it.getString(nameCol) ?: continue else continue
                    if (!isBookFile(name)) continue
                    val size = if (sizeCol >= 0) it.getLong(sizeCol) else 0L
                    val mod = if (modCol >= 0) it.getLong(modCol) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    out.add(
                        MyBook(
                            displayName(name), name, size, mod,
                            safUri = docUri, sortKey = mod
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.d("MyBooks SAF list failed: ${e.message}")
        }
        return out
    }

    /** Відкрити зовнішньою читалкою. Без пакета — системний вибір;
     * з пакетом — одразу в ньому (вибір запам'ятовується при першому відкритті). */
    fun open(context: Context, book: MyBook, s: AppStrings, packageName: String? = null) {
        try {
            val intent = openIntent(context, book) ?: run {
                Toast.makeText(context, s.fileNotReady, Toast.LENGTH_SHORT).show()
                return
            }
            if (packageName != null) {
                intent.setPackage(packageName)
                context.startActivity(intent)
            } else {
                val chooser = Intent.createChooser(intent, s.openBook)
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (e: Exception) {
            AppLog.d("MyBooks open failed: ${book.fileName}: ${e.message}")
            Toast.makeText(context, "${s.openFailed}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Читалка, вибрана користувачем для «Відкрити» (запам'ятовується при першому виборі). */
    data class ReaderApp(val label: String, val packageName: String)

    private const val READER_PREFS = "allbooks_open_with"
    private const val KEY_READER_PKG = "package"

    fun savedReaderPackage(context: Context): String? =
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_READER_PKG, null)?.takeIf { it.isNotBlank() }

    fun saveReaderPackage(context: Context, packageName: String) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_READER_PKG, packageName).apply()
    }

    /** Скинути запам'ятовану читалку: наступне «Відкрити» знову запитає. */
    fun clearReaderPackage(context: Context) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_READER_PKG).apply()
    }

    /** Назва застосунку за пакетом (null — застосунок не встановлено). */
    fun readerAppLabel(context: Context, packageName: String): String? =
        try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            info.loadLabel(pm).toString()
        } catch (e: Exception) {
            null
        }

    /** Чи може застосунок з пакета ще відкрити цю книгу (не видалено). */
    fun canOpenWith(context: Context, book: MyBook, packageName: String): Boolean =
        readerCandidates(context, book).any { it.packageName == packageName }

    /** Застосунки, що вміють відкрити цю книгу (без нашого власного). */
    fun readerCandidates(context: Context, book: MyBook): List<ReaderApp> {
        val intent = openIntent(context, book) ?: return emptyList()
        val pm = context.packageManager
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ReaderApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun openIntent(context: Context, book: MyBook): Intent? {
        val uri = when {
            book.file != null -> try {
                FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, book.file)
            } catch (e: Exception) {
                null
            }
            book.safUri != null -> book.safUri
            book.dmId != null -> Downloads.uriFor(context, book.dmId)
            else -> null
        } ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(book.fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Видалити файл книги (і запис завантаження, якщо він є). */
    fun delete(context: Context, book: MyBook, s: AppStrings) {
        var ok = true
        try {
            when {
                book.dmId != null -> Downloads.remove(context, book.dmId)
                book.file != null -> ok = book.file.delete()
                book.safUri != null -> {
                    ok = DocumentsContract.deleteDocument(context.contentResolver, book.safUri)
                    // Прибрати і запис завантаження цього ж файлу, щоб не лишився
                    // «мертвий» рядок на екрані Завантаження.
                    Downloads.list(context, s)
                        .filter { it.safDocUri.isNotBlank() && it.fileName == book.fileName }
                        .forEach { Downloads.remove(context, it.id) }
                }
            }
        } catch (e: Exception) {
            AppLog.d("MyBooks delete failed: ${book.fileName}: ${e.message}")
            ok = false
        }
        if (!ok) {
            Toast.makeText(context, s.errorTitle, Toast.LENGTH_SHORT).show()
        }
    }
}
