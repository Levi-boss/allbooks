package app.allbooks

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.allbooks.net.NetSettings
import app.allbooks.opds.Entry
import app.allbooks.opds.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Вбудована читалка: FB2, EPUB і TXT.
 * Прокрутка списком блоків (без посторінкової розбивки), розмір шрифту
 * +/-, запам'ятовування останньої позиції по книзі. Просто і надійно:
 * парсинг через XmlPullParser, без сторонніх залежностей.
 */

/** Завантажена книга, яку можна читати всередині застосунку. */
data class ReaderBook(
    val displayName: String,
    val fileName: String,
    val file: File? = null,
    val safUri: Uri? = null,
    val dmId: Long? = null
) {
    companion object {
        fun fromMyBook(b: MyBook): ReaderBook =
            ReaderBook(b.displayName, b.fileName, b.file, b.safUri, b.dmId)
    }
}

/** Блок тексту книги: заголовок або абзац. */
sealed interface Block {
    data class Heading(val text: String, val level: Int) : Block
    data class Para(val text: String) : Block
}

object Reader {
    fun supports(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".fb2") || lower.endsWith(".fb2.zip") ||
            lower.endsWith(".epub") || lower.endsWith(".txt")
    }

    /** Ім'я файлу, яке застосунок дає при завантаженні (те саме, що в Downloads.start). */
    fun expectedFileName(entry: Entry, link: Link): String {
        val safe = entry.title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .trim().take(80).ifEmpty { "book" }
        return "$safe.${Downloads.extension(link)}"
    }

    /** Карта «ім'я файлу → завантажена книга» (диск, SAF, DownloadManager). */
    fun downloadedMap(
        context: Context,
        settings: NetSettings,
        s: AppStrings
    ): Map<String, MyBook> =
        try {
            MyBooks.list(context, settings, s).associateBy { it.fileName }
        } catch (e: Exception) {
            emptyMap()
        }

    /** Знайти завантажений файл для книги з каталогу (для кнопки «Читати»). */
    fun findDownloaded(
        entry: Entry,
        downloaded: Map<String, MyBook>
    ): ReaderBook? {
        if (downloaded.isEmpty()) return null
        for (link in entry.links) {
            if (!link.isAcquisition) continue
            val b = downloaded[expectedFileName(entry, link)] ?: continue
            if (!supports(b.fileName)) continue
            return ReaderBook.fromMyBook(b)
        }
        return null
    }

    fun openStream(context: Context, book: ReaderBook): InputStream? {
        return try {
            when {
                book.file != null -> FileInputStream(book.file)
                book.safUri != null -> context.contentResolver.openInputStream(book.safUri)
                book.dmId != null -> {
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val uri = dm.getUriForDownloadedFile(book.dmId) ?: return null
                    if (uri.scheme == "file") {
                        FileInputStream(File(uri.path ?: return null))
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            AppLog.d("Reader open failed: ${book.fileName}: ${e.message}")
            null
        }
    }

    /** Розпарсити книгу у список блоків; null — не вдалося відкрити/розпізнати. */
    suspend fun load(context: Context, book: ReaderBook): List<Block>? =
        withContext(Dispatchers.IO) {
            try {
                val stream = openStream(context, book) ?: return@withContext null
                stream.use {
                    val lower = book.fileName.lowercase()
                    val blocks = when {
                        lower.endsWith(".fb2.zip") -> parseFb2Zip(it)
                        lower.endsWith(".fb2") -> parseFb2(it)
                        lower.endsWith(".epub") -> parseEpub(it)
                        lower.endsWith(".txt") -> parseTxt(it)
                        else -> null
                    }
                    if (blocks.isNullOrEmpty()) null else blocks
                }
            } catch (e: Exception) {
                AppLog.d("Reader load failed: ${book.fileName}: ${e.message}")
                null
            }
        }

    // --- Підготовка XML-тексту: кодування з декларації, без DOCTYPE,
    // HTML-сутності (nbsp, mdash тощо), які XML-парсер не розуміє. ---

    private val HTML_ENTITIES = listOf(
        "&nbsp;" to "\u00A0",
        "&mdash;" to "—", "&ndash;" to "–",
        "&laquo;" to "«", "&raquo;" to "»",
        "&hellip;" to "…", "&copy;" to "©",
        "&reg;" to "®", "&trade;" to "™",
        "&sect;" to "§", "&deg;" to "°",
        "&times;" to "×", "&minus;" to "−",
        "&lsquo;" to "‘", "&rsquo;" to "’",
        "&ldquo;" to "“", "&rdquo;" to "”",
        "&bull;" to "•"
    )

    private fun cleanXml(bytes: ByteArray): String {
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
        for ((from, to) in HTML_ENTITIES) text = text.replace(from, to)
        return text
    }

    private fun newParser(text: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), "UTF-8")
        return parser
    }

    /** Збирає заголовки й абзаци з подій парсера. */
    private class BlockCollector {
        val blocks = mutableListOf<Block>()
        private var buf: StringBuilder? = null
        private var kind = 0 // 0 — нічого, 1 — заголовок, 2 — абзац
        private var level = 1

        fun startHeading(level: Int) {
            flush()
            kind = 1
            this.level = level
            buf = StringBuilder()
        }

        fun startPara() {
            flush()
            kind = 2
            buf = StringBuilder()
        }

        fun text(t: String) {
            buf?.append(t)
        }

        fun br() {
            if (kind == 2) buf?.append('\n')
        }

        fun flush() {
            val b = buf
            val k = kind
            buf = null
            kind = 0
            if (b == null) return
            val text = b.toString().trim()
            if (text.isEmpty()) return
            if (k == 1) blocks.add(Block.Heading(text, level))
            else blocks.add(Block.Para(text))
        }
    }

    // --- FB2: тільки перше <body> (основний текст, не примітки);
    // заголовки — з <title>, абзаци — з <p> і <v> (вірші). ---

    private fun parseFb2(stream: InputStream): List<Block> {
        val parser = newParser(cleanXml(stream.readBytes()))
        val out = BlockCollector()
        var event = parser.eventType
        var bodySeen = 0
        var inMainBody = false
        var sectionDepth = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "body") {
                        bodySeen++
                        inMainBody = bodySeen == 1
                        sectionDepth = 0
                    } else if (inMainBody) {
                        when (name) {
                            "section" -> sectionDepth++
                            "title" -> out.startHeading(sectionDepth.coerceIn(1, 3))
                            "subtitle" -> out.startHeading(2)
                            "p", "v" -> out.startPara()
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inMainBody) out.text(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "body") {
                        out.flush()
                        inMainBody = false
                    } else if (inMainBody) {
                        when (name) {
                            "section" -> sectionDepth = (sectionDepth - 1).coerceAtLeast(0)
                            "title", "subtitle", "p", "v" -> out.flush()
                        }
                    }
                }
            }
            event = parser.next()
        }
        out.flush()
        return out.blocks
    }

    private fun parseFb2Zip(stream: InputStream): List<Block> {
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.lowercase().endsWith(".fb2")) {
                    return parseFb2(zip)
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return emptyList()
    }

    // --- EPUB: container.xml → OPF (manifest + spine) → глави XHTML. ---

    private fun parseEpub(stream: InputStream): List<Block> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val container = entries["META-INF/container.xml"] ?: return emptyList()
        val rootPath = rootfilePath(container) ?: return emptyList()
        val opfDir = rootPath.substringBeforeLast('/', "")
        val opf = entries[rootPath] ?: return emptyList()
        val (manifest, spine) = parseOpf(opf)
        val blocks = mutableListOf<Block>()
        for (id in spine) {
            val item = manifest[id] ?: continue
            val href = item.first
            val isXhtml = item.second == "application/xhtml+xml" ||
                href.endsWith(".xhtml", ignoreCase = true) ||
                href.endsWith(".htm", ignoreCase = true) ||
                href.endsWith(".html", ignoreCase = true)
            if (!isXhtml) continue
            val bytes = entries[resolvePath(opfDir, href)] ?: continue
            blocks.addAll(parseXhtml(bytes))
        }
        return blocks
    }

    private fun rootfilePath(containerBytes: ByteArray): String? {
        val parser = newParser(cleanXml(containerBytes))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.lowercase() == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            event = parser.next()
        }
        return null
    }

    private fun parseOpf(opfBytes: ByteArray): Pair<Map<String, Pair<String, String>>, List<String>> {
        val manifest = mutableMapOf<String, Pair<String, String>>()
        val spine = mutableListOf<String>()
        val parser = newParser(cleanXml(opfBytes))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val type = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id != null && href != null) manifest[id] = href to type
                    }
                    "itemref" -> {
                        parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                    }
                }
            }
            event = parser.next()
        }
        return manifest to spine
    }

    private fun resolvePath(base: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val parts = mutableListOf<String>()
        if (!clean.startsWith("/") && base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun headingLevel(name: String): Int? {
        if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
            return name[1].digitToInt()
        }
        return null
    }

    private fun parseXhtml(bytes: ByteArray): List<Block> {
        val parser = newParser(cleanXml(bytes))
        val out = BlockCollector()
        var event = parser.eventType
        var inHead = false
        var skipDepth = 0 // всередині <script> або <style>
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        name == "head" -> inHead = true
                        name == "script" || name == "style" -> skipDepth++
                        skipDepth > 0 || inHead -> {}
                        name == "br" -> out.br()
                        headingLevel(name) != null -> out.startHeading(headingLevel(name)!!)
                        name == "p" -> out.startPara()
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0 && !inHead) out.text(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        name == "head" -> inHead = false
                        name == "script" || name == "style" ->
                            skipDepth = (skipDepth - 1).coerceAtLeast(0)
                        skipDepth > 0 || inHead -> {}
                        headingLevel(name) != null || name == "p" -> out.flush()
                    }
                }
            }
            event = parser.next()
        }
        out.flush()
        return out.blocks
    }

    // --- TXT: абзаци розділені порожніми рядками; перенесені рядки зливаються. ---

    private fun parseTxt(stream: InputStream): List<Block> {
        val text = stream.bufferedReader(Charsets.UTF_8).readText()
        val blocks = mutableListOf<Block>()
        val cur = StringBuilder()
        fun flush() {
            val t = cur.toString().trim()
            cur.clear()
            if (t.isNotEmpty()) blocks.add(Block.Para(t))
        }
        for (line in text.lines()) {
            if (line.isBlank()) {
                flush()
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(line.trim())
            }
        }
        flush()
        return blocks
    }
}

/** Розмір шрифту читалки і остання позиція по книзі (SharedPreferences). */
object ReaderStore {
    private const val PREFS = "reader"

    fun fontSize(c: Context): Float =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("font_sp", 18f)

    fun saveFontSize(c: Context, size: Float) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("font_sp", size).apply()
    }

    fun position(c: Context, fileName: String): Pair<Int, Int> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pos_$fileName", null) ?: return 0 to 0
        val parts = raw.split(':')
        val index = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return index.coerceAtLeast(0) to offset.coerceAtLeast(0)
    }

    fun savePosition(c: Context, fileName: String, index: Int, offset: Int) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pos_$fileName", "$index:$offset").apply()
    }

    /** Остання відкрита книга (ім'я файлу + назва) — для «Продовжити читання». */
    fun lastRead(c: Context): Pair<String, String>? {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = prefs.getString("last_file", null) ?: return null
        val name = prefs.getString("last_name", null) ?: return null
        return file to name
    }

    fun saveLastRead(c: Context, book: ReaderBook) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_file", book.fileName)
            .putString("last_name", book.displayName).apply()
    }

    fun clearLastRead(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("last_file").remove("last_name").apply()
    }
}

/** Екран читання: заголовки й абзаци списком, A- / A+ — розмір шрифту. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(s: AppStrings, book: ReaderBook, dark: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<Block>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(ReaderStore.fontSize(context)) }
    val savedPos = remember(book) { ReaderStore.position(context, book.fileName) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPos.first,
        initialFirstVisibleItemScrollOffset = savedPos.second
    )

    fun savePosition() {
        ReaderStore.savePosition(
            context, book.fileName,
            listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset
        )
    }

    LaunchedEffect(book) {
        val parsed = Reader.load(context, book)
        if (parsed.isNullOrEmpty()) {
            failed = true
        } else {
            blocks = parsed
            ReaderStore.saveLastRead(context, book)
            if (savedPos.first >= parsed.size) {
                listState.scrollToItem(0, 0)
            }
        }
    }

    BackHandler { savePosition(); onBack() }
    DisposableEffect(Unit) { onDispose { savePosition() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(book.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    TextButton(onClick = { savePosition(); onBack() }) { Text(s.back) }
                },
                actions = {
                    TextButton(onClick = {
                        fontSize = (fontSize - 2f).coerceAtLeast(12f)
                        ReaderStore.saveFontSize(context, fontSize)
                    }) { Text("A−") }
                    TextButton(onClick = {
                        fontSize = (fontSize + 2f).coerceAtMost(32f)
                        ReaderStore.saveFontSize(context, fontSize)
                    }) { Text("A+") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (dark) Color(0xFF121212) else Color.White
                )
            )
        }
    ) { padding ->
        when {
            failed -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) { Text(s.readFailed) }
            blocks == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            else -> {
                val items = blocks!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    itemsIndexed(items, key = { index, _ -> index }) { _, block ->
                        when (block) {
                            is Block.Heading -> Text(
                                block.text,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                fontSize = (fontSize + 4f).sp,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                            is Block.Para -> Text(
                                block.text,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize + 8f).sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
