package app.allbooks

import app.allbooks.opds.Author
import app.allbooks.opds.Entry
import app.allbooks.opds.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Мінімальні unit-тести чистої логіки (без android.*):
 * вибір формату для завантаження, ім'я файлу, розмір.
 * Запуск локально: ./gradlew testDebugUnitTest
 * (CI поки запускає тільки assembleDebug, тому тести там не біжать).
 */
class BookLogicTest {

    private val acq = "http://opds-spec.org/acquisition"

    private fun entryWith(vararg links: Link) = Entry(
        id = "1",
        title = "Test Book",
        authors = listOf(Author("Автор", "")),
        summary = "",
        links = links.toList()
    )

    @Test
    fun preferredLink_prefersFb2OverEpub() {
        val entry = entryWith(
            Link("http://x/1.epub", acq, "application/epub+zip", "EPUB"),
            Link("http://x/1.fb2", acq, "application/fb2+xml", "FB2")
        )
        assertEquals("http://x/1.fb2", Downloads.preferredLink(entry)?.href)
    }

    @Test
    fun preferredLink_prefersFb2ZipOverEpubAndTxt() {
        val entry = entryWith(
            Link("http://x/1.txt", acq, "text/plain", "TXT"),
            Link("http://x/1.epub", acq, "application/epub+zip", "EPUB"),
            Link("http://x/1.fb2.zip", acq, "application/fb2+zip", "FB2.ZIP")
        )
        assertEquals("http://x/1.fb2.zip", Downloads.preferredLink(entry)?.href)
    }

    @Test
    fun preferredLink_returnsNullWithoutAcquisitionLinks() {
        val entry = entryWith(
            Link("http://x/sub", "subsection", "application/atom+xml", "Розділ")
        )
        assertNull(Downloads.preferredLink(entry))
    }

    @Test
    fun expectedFileName_sanitizesTitleAndAddsExtension() {
        val entry = entryWith(
            Link("http://x/1.fb2", acq, "application/fb2+xml", "FB2")
        )
        val link = entry.links[0]
        assertEquals("Test Book.fb2", Reader.expectedFileName(entry, link))
        assertEquals("A B C D.fb2", Reader.expectedFileName(entry.copy(title = "A/B:C*D"), link))
        assertEquals("book.fb2", Reader.expectedFileName(entry.copy(title = ""), link))
    }

    @Test
    fun expectedFileName_fb2ZipKeepsDoubleExtension() {
        val entry = entryWith(
            Link("http://x/1.fb2.zip", acq, "application/fb2+zip", "FB2.ZIP")
        )
        assertEquals("Test Book.fb2.zip", Reader.expectedFileName(entry, entry.links[0]))
    }

    @Test
    fun formatSize_kilobytesAndEmpty() {
        assertEquals("", MyBooks.formatSize(0, Strings.uk))
        assertEquals("2 КБ", MyBooks.formatSize(2048, Strings.uk))
        assertEquals("0 КБ", MyBooks.formatSize(512, Strings.uk))
    }
}
