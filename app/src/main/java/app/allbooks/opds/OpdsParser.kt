package app.allbooks.opds

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URL

object OpdsParser {

    fun parse(input: InputStream, baseUrl: String): Feed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)

        var feedTitle = ""
        val entries = mutableListOf<Entry>()
        val feedLinks = mutableListOf<Link>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> {
                        val t = readText(p)
                        if (feedTitle.isEmpty()) feedTitle = t
                    }
                    "link" -> feedLinks.add(readLink(p, baseUrl))
                    "entry" -> entries.add(readEntry(p, baseUrl))
                }
            }
            event = p.next()
        }

        val next = feedLinks.firstOrNull { it.rel == "next" }?.href
        val search = feedLinks.firstOrNull {
            it.rel == "search" && it.type.contains("atom")
        }?.href

        return Feed(feedTitle, entries, next, search)
    }

    private fun readEntry(p: XmlPullParser, base: String): Entry {
        var id = ""
        var title = ""
        var summary = ""
        val authors = mutableListOf<Author>()
        val links = mutableListOf<Link>()
        val categories = mutableListOf<String>()
        var language = ""
        var updated = ""

        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.name == "entry") break
            if (ev != XmlPullParser.START_TAG) continue

            when (p.name) {
                "id" -> id = readText(p)
                "title" -> title = readText(p)
                "link" -> links.add(readLink(p, base))
                "category" -> {
                    val term = p.getAttributeValue(null, "term")?.trim() ?: ""
                    if (term.isNotBlank()) categories.add(term)
                    skip(p)
                }
                "dc:language" -> language = readText(p)
                "updated" -> updated = readText(p)
                "author" -> {
                    val a = readAuthor(p, base)
                    if (a != null) authors.add(a)
                }
                "content", "summary" -> {
                    val t = readText(p)
                    if (t.isNotBlank() && summary.isBlank()) summary = cleanHtml(t)
                }
            }
        }
        return Entry(id, title, authors, summary, links, categories, language, updated)
    }

    private fun readAuthor(p: XmlPullParser, base: String): Author? {
        var name = ""
        var uri = ""
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    when (p.name) {
                        "name" -> name = readText(p)
                        "uri" -> uri = readText(p)
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return if (name.isBlank()) null else Author(name, resolve(base, uri))
    }

    private fun readLink(p: XmlPullParser, base: String): Link {
        val href = resolve(base, p.getAttributeValue(null, "href") ?: "")
        val link = Link(
            href = href,
            rel = p.getAttributeValue(null, "rel") ?: "",
            type = p.getAttributeValue(null, "type") ?: "",
            title = p.getAttributeValue(null, "title") ?: ""
        )
        skip(p)
        return link
    }

    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun resolve(base: String, href: String): String {
        if (href.isEmpty()) return href
        return try {
            URL(URL(base), href).toString()
        } catch (e: Exception) {
            href
        }
    }

    private fun cleanHtml(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString().trim()
}