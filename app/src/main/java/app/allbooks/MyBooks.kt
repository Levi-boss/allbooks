package app.allbooks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Xml
import android.widget.Toast
import androidx.core.content.FileProvider
import app.allbooks.net.NetSettings
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Завантажена книга з папки завантажень.
 * Джерело — одне з трьох: файл на диску, документ SAF або запис DownloadManager.
 */
/** Справжні метадані книги (назва, автор, серія) зсередини файлу. */
data class BookMeta(
    val title: String,
    val author: String,
    val series: String
)

data class MyBook(
    val displayName: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val file: File? = null,
    val safUri: Uri? = null,
    val dmId: Long? = null,
    val sortKey: Long = modified,
    val meta: BookMeta? = null
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

    // --- Метадані зсередини файлу (назва, автор, серія) замість імені
    // файлу. Кеш — SharedPreferences, ключ "<ім'я>|<розмір>|<зміна>",
    // щоб не розбирати книги при кожному відкритті списку. ---

    private const val META_PREFS = "allbooks_book_meta"

    private fun metaKey(book: MyBook): String =
        "${book.fileName}|${book.size}|${book.modified}"

    /** Метадані з кешу (null — ще не розбирали або нема чого показати). */
    fun cachedMeta(context: Context, book: MyBook): BookMeta? {
        val raw = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getString(metaKey(book), null) ?: return null
        val p = raw.split('\u0001')
        val title = p.getOrNull(0) ?: ""
        val author = p.getOrNull(1) ?: ""
        val series = p.getOrNull(2) ?: ""
        if (title.isBlank() && author.isBlank() && series.isBlank()) return null
        return BookMeta(title, author, series)
    }

    private fun saveMetaCache(context: Context, book: MyBook, meta: BookMeta) {
        val clean = listOf(meta.title, meta.author, meta.series)
            .map { it.replace(Regex("[\u0001\\n\\r]+"), " ").trim() }
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putString(metaKey(book), clean.joinToString("\u0001")).apply()
    }

    /** Метадані книги: з кешу або розбір файлу. Тільки FB2 і EPUB;
     * решта форматів метаданих не несуть. Викликати поза головним потоком. */
    fun loadMeta(context: Context, book: MyBook): BookMeta? {
        cachedMeta(context, book)?.let { return it }
        val lower = book.fileName.lowercase()
        if (!lower.endsWith(".fb2") && !lower.endsWith(".fb2.zip") &&
            !lower.endsWith(".epub")
        ) return null
        val meta = try {
            val stream = Reader.openStream(context, ReaderBook.fromMyBook(book))
                ?: return null
            stream.use {
                when {
                    lower.endsWith(".fb2.zip") -> parseFb2ZipMeta(it)
                    lower.endsWith(".fb2") -> parseFb2Meta(it)
                    lower.endsWith(".epub") -> parseEpubMeta(it)
                    else -> null
                }
            }
        } catch (e: Exception) {
            AppLog.d("BookMeta failed: ${book.fileName}: ${e.message}")
            null
        }
        if (meta != null) saveMetaCache(context, book, meta)
        return meta
    }

    // Початок XML: кодування з декларації, без DOCTYPE, HTML-сутності —
    // як у читалки, щоб парсер не падав на них.
    private val META_ENTITIES = listOf(
        "&nbsp;" to "\u00A0",
        "&mdash;" to "—", "&ndash;" to "–",
        "&laquo;" to "«", "&raquo;" to "»",
        "&hellip;" to "…",
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'"
    )

    private fun decodeXmlHead(bytes: ByteArray): String {
        val head = String(bytes.take(200).toByteArray(), Charsets.US_ASCII)
        val enc = Regex("encoding=[\"']([^\"']+)[\"']").find(head)
            ?.groupValues?.getOrNull(1) ?: "UTF-8"
        val charset = try {
            Charset.forName(enc)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        var text = String(bytes, charset)
        text = text.replace(
            Regex("<!DOCTYPE[^\\[]*\\[[^\\]]*\\]>", RegexOption.DOT_MATCHES_ALL), ""
        )
        text = text.replace(Regex("<!DOCTYPE[^>]*>"), "")
        for ((from, to) in META_ENTITIES) text = text.replace(from, to)
        return text
    }

    private fun newMetaParser(text: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), "UTF-8")
        return parser
    }

    // FB2: тільки початок файлу (перші 64 КБ) — <title-info> там завжди.
    // Початок обрізаний, тож помилка парсера в кінці — норма: беремо те,
    // що встигли зібрати.
    private fun parseFb2Meta(stream: InputStream): BookMeta? {
        val bytes = ByteArray(64 * 1024)
        var read = 0
        while (read < bytes.size) {
            val n = stream.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        if (read == 0) return null
        val parser = newMetaParser(decodeXmlHead(bytes.copyOf(read)))
        var title = ""
        var authorFirst = ""
        var authorLast = ""
        var series = ""
        var seriesNum = ""
        var inTitleInfo = false
        var inAuthor = false
        var capture: String? = null
        val buf = StringBuilder()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase()
                        when {
                            name == "title-info" -> inTitleInfo = true
                            !inTitleInfo -> {}
                            name == "author" -> inAuthor = true
                            name == "book-title" -> { capture = "title"; buf.clear() }
                            name == "first-name" && inAuthor -> { capture = "first"; buf.clear() }
                            name == "last-name" && inAuthor -> { capture = "last"; buf.clear() }
                            name == "sequence" -> {
                                series = parser.getAttributeValue(null, "name") ?: ""
                                seriesNum = parser.getAttributeValue(null, "number") ?: ""
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (capture != null) buf.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase()
                        when (name) {
                            "title-info" -> break
                            "author" -> inAuthor = false
                            "book-title" -> { title = buf.toString().trim(); capture = null }
                            "first-name" -> { authorFirst = buf.toString().trim(); capture = null }
                            "last-name" -> { authorLast = buf.toString().trim(); capture = null }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Обрізаний початок: беремо зібране.
        }
        if (title.isBlank() && authorFirst.isBlank() &&
            authorLast.isBlank() && series.isBlank()
        ) return null
        val author = listOf(authorFirst, authorLast)
            .filter { it.isNotBlank() }.joinToString(" ")
        val seriesFull = if (series.isBlank()) "" else if (seriesNum.isBlank()) series
            else "$series ($seriesNum)"
        return BookMeta(title, author, seriesFull)
    }

    private fun parseFb2ZipMeta(stream: InputStream): BookMeta? {
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.lowercase().endsWith(".fb2")) {
                    return parseFb2Meta(zip)
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return null
    }

    // EPUB: container.xml → OPF → dc:title, dc:creator, calibre:series.
    // Читаємо лише потрібні записи архіву, решту пропускаємо.
    private fun parseEpubMeta(stream: InputStream): BookMeta? {
        var container: ByteArray? = null
        val opfs = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val lower = e.name.lowercase()
                if (!e.isDirectory && lower == "meta-inf/container.xml") {
                    container = zip.readBytes()
                } else if (!e.isDirectory && lower.endsWith(".opf")) {
                    opfs[lower] = zip.readBytes()
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        if (opfs.isEmpty()) return null
        val opfBytes = container?.let { rootfilePath(it) }?.lowercase()
            ?.let { opfs[it] } ?: opfs.values.firstOrNull() ?: return null
        val parser = newMetaParser(decodeXmlHead(opfBytes))
        var title = ""
        var creator = ""
        var series = ""
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "dc:title" -> if (title.isBlank()) title = nextMetaText(parser)
                        "dc:creator" -> if (creator.isBlank()) creator = nextMetaText(parser)
                        "meta" -> {
                            if (parser.getAttributeValue(null, "name")
                                    ?.lowercase() == "calibre:series"
                            ) {
                                series = parser.getAttributeValue(null, "content") ?: ""
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Беремо зібране.
        }
        if (title.isBlank() && creator.isBlank() && series.isBlank()) return null
        return BookMeta(title.trim(), creator.trim(), series.trim())
    }

    private fun rootfilePath(containerBytes: ByteArray): String? {
        return try {
            val parser = newMetaParser(decodeXmlHead(containerBytes))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name.lowercase() == "rootfile"
                ) {
                    return parser.getAttributeValue(null, "full-path")
                }
                event = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // Текст елемента: парсер стоїть на START_TAG, беремо наступний TEXT.
    private fun nextMetaText(parser: XmlPullParser): String {
        return try {
            if (parser.next() == XmlPullParser.TEXT) parser.text.trim() else ""
        } catch (e: Exception) {
            ""
        }
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

    /** Вибір читалки для «Відкрити» — у Налаштуваннях: питати щоразу
     * або одразу у вбудованій читалці. Запам'ятовування зовнішньої
     * читалки прибрано: «питати щоразу» має питати щоразу. */
    data class ReaderApp(val label: String, val packageName: String)

    private const val READER_PREFS = "allbooks_open_with"
    private const val KEY_BUILT_IN = "built_in"

    /** «Відкрити» — одразу у вбудованій читалці, без запиту. */
    fun preferBuiltIn(context: Context): Boolean =
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUILT_IN, false)

    fun setPreferBuiltIn(context: Context, value: Boolean) {
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BUILT_IN, value).apply()
    }

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
