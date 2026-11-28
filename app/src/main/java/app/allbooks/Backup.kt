package app.allbooks

import android.content.Context
import app.allbooks.net.ProxyMode
import app.allbooks.opds.Author
import app.allbooks.opds.Entry
import app.allbooks.opds.Link
import org.json.JSONArray
import org.json.JSONObject

/**
 * Резервна копія JSON (крок 7 плану): налаштування мережі й теми,
 * власні каталоги, підписки, закладки каталогів, закладки читалки,
 * позиції читання. Без хмарних SDK — експорт/імпорт через системний
 * вибір файлу. Паролі (проксі й каталогів) у копію НЕ йдуть: після
 * імпорту їх просить ввести заново діалог логіна (401) або Налаштування.
 * Формат — з полем version, при імпорті перевіряється.
 */
object Backup {
    const val VERSION = 1

    data class ImportResult(
        val catalogs: Int,
        val subscriptions: Int,
        val favourites: Int,
        val positions: Int,
        val bookmarks: Int
    )

    /** JSON-текст копії поточного стану. */
    fun export(c: Context): String {
        val root = JSONObject()
        root.put("version", VERSION)

        val s = SettingsStore.load(c)
        val settings = JSONObject()
        settings.put("proxy_mode", s.proxyMode.name)
        settings.put("proxy_host", s.proxyHost)
        settings.put("proxy_port", s.proxyPort)
        settings.put("proxy_user", s.proxyUser)
        settings.put("use_doh", s.useDoh)
        settings.put("start_catalog", s.startCatalog)
        settings.put("download_folder", s.downloadFolder)
        settings.put("theme", s.theme)
        settings.put("notify_new_books", s.notifyNewBooks)
        settings.put("feed_grid", s.feedGrid)
        settings.put("amoled", s.amoled)
        root.put("settings", settings)

        val catalogs = JSONArray()
        CustomStore.load(c).forEach { cat ->
            val o = JSONObject()
            o.put("name", cat.name)
            o.put("url", cat.urls.firstOrNull() ?: "")
            o.put("user", cat.user)
            catalogs.put(o)
        }
        root.put("catalogs", catalogs)

        val subs = JSONArray()
        SubscriptionsStore.load(c).forEach { sub ->
            val o = JSONObject()
            o.put("type", sub.type)
            o.put("name", sub.name)
            o.put("url", sub.url)
            o.put("known_ids", JSONArray(sub.knownIds.toList()))
            o.put("new_ids", JSONArray(sub.newIds.toList()))
            o.put("last_check", sub.lastCheck)
            o.put("user", sub.user)
            subs.put(o)
        }
        root.put("subscriptions", subs)

        val favs = JSONArray()
        FavouritesStore.load(c).forEach { fav ->
            val o = JSONObject()
            val e = fav.entry
            o.put("id", e.id)
            o.put("title", e.title)
            val authors = JSONArray()
            e.authors.forEach { a ->
                authors.put(JSONObject().put("name", a.name).put("uri", a.uri))
            }
            o.put("authors", authors)
            o.put("summary", e.summary)
            val links = JSONArray()
            e.links.forEach { l ->
                links.put(
                    JSONObject().put("href", l.href).put("rel", l.rel)
                        .put("type", l.type).put("title", l.title)
                )
            }
            o.put("links", links)
            o.put("categories", JSONArray(e.categories))
            o.put("language", e.language)
            o.put("updated", e.updated)
            o.put("catalog_name", fav.catalogName)
            o.put("catalog_url", fav.catalogUrl)
            favs.put(o)
        }
        root.put("favourites", favs)

        val reader = JSONObject()
        val positions = JSONObject()
        ReaderStore.allPositions(c).forEach { (k, v) -> positions.put(k, v) }
        reader.put("positions", positions)
        val bookmarks = JSONObject()
        ReaderStore.allBookmarks(c).forEach { (file, marks) ->
            val arr = JSONArray()
            marks.forEach { m ->
                arr.put(JSONObject().put("index", m.index).put("snippet", m.snippet))
            }
            bookmarks.put(file, arr)
        }
        reader.put("bookmarks", bookmarks)
        ReaderStore.lastRead(c)?.let { (file, name) ->
            reader.put(
                "last_read",
                JSONObject().put("file", file).put("name", name)
            )
        }
        root.put("reader", reader)

        return root.toString(2)
    }

    // Корінь копії; невірний формат або версія — IllegalArgumentException.
    private fun parseRoot(json: String): JSONObject {
        val root = JSONObject(json)
        if (root.optInt("version", -1) != VERSION) {
            throw IllegalArgumentException("unsupported backup version")
        }
        return root
    }

    /** Налаштування з копії поверх поточних. Проксі (режим, хост, порт,
     * логін), пароль проксі, SAF-папка і прапор вибору папки — пристроєві,
     * лишаються як є: підмінена копія не повинна тихо міняти проксі.
     * Не зберігає: збереження — справа викликача (щоб застосувалися тема
     * і перевірка підписок через звичайний шлях Налаштувань). */
    fun importSettings(c: Context, json: String) = importSettings(c, parseRoot(json))

    private fun importSettings(c: Context, root: JSONObject) =
        SettingsStore.load(c).let { cur ->
            val o = root.optJSONObject("settings") ?: return@let cur
            cur.copy(
                useDoh = o.optBoolean("use_doh", cur.useDoh),
                startCatalog = o.optString("start_catalog", cur.startCatalog),
                downloadFolder = Downloads.cleanFolderName(
                    o.optString("download_folder", cur.downloadFolder)
                ),
                theme = o.optString("theme", cur.theme),
                notifyNewBooks = o.optBoolean("notify_new_books", cur.notifyNewBooks),
                feedGrid = o.optBoolean("feed_grid", cur.feedGrid),
                amoled = o.optBoolean("amoled", cur.amoled)
            )
        }

    /** Каталоги, підписки, закладки і читалка з копії. Те, чого ще нема, —
     * додається; позиції і закладки читалки імпортовані перемагають. */
    fun importData(c: Context, json: String): ImportResult {
        val root = parseRoot(json)
        var addedCatalogs = 0
        var addedSubs = 0
        var addedFavs = 0

        root.optJSONArray("catalogs")?.let { arr ->
            val current = CustomStore.load(c).toMutableList()
            val haveUrls = current.map { it.urls.firstOrNull() ?: "" }.toMutableSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val url = o.optString("url", "").trim()
                if (name.isBlank() || url.isBlank() || url in haveUrls) continue
                current.add(
                    Catalog(name, "", listOf(url), user = o.optString("user", ""))
                )
                haveUrls.add(url)
                addedCatalogs++
            }
            if (addedCatalogs > 0) CustomStore.save(c, current)
        }

        root.optJSONArray("subscriptions")?.let { arr ->
            val current = SubscriptionsStore.load(c).toMutableList()
            val haveUrls = current.map { it.url }.toMutableSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "").trim()
                if (url.isBlank() || url in haveUrls) continue
                current.add(
                    Subscription(
                        type = o.optString("type", "author"),
                        name = o.optString("name", ""),
                        url = url,
                        knownIds = o.optStringSet("known_ids"),
                        newIds = o.optStringSet("new_ids"),
                        lastCheck = o.optLong("last_check", 0L),
                        user = o.optString("user", "")
                    )
                )
                haveUrls.add(url)
                addedSubs++
            }
            if (addedSubs > 0) SubscriptionsStore.save(c, current)
        }

        root.optJSONArray("favourites")?.let { arr ->
            val current = FavouritesStore.load(c).toMutableList()
            val haveIds = current.map { it.entry.id }.toMutableSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isBlank() || id in haveIds) continue
                val authors = mutableListOf<Author>()
                o.optJSONArray("authors")?.let { a ->
                    for (j in 0 until a.length()) {
                        val ao = a.optJSONObject(j) ?: continue
                        authors.add(
                            Author(ao.optString("name", ""), ao.optString("uri", ""))
                        )
                    }
                }
                val links = mutableListOf<Link>()
                o.optJSONArray("links")?.let { a ->
                    for (j in 0 until a.length()) {
                        val lo = a.optJSONObject(j) ?: continue
                        links.add(
                            Link(
                                lo.optString("href", ""), lo.optString("rel", ""),
                                lo.optString("type", ""), lo.optString("title", "")
                            )
                        )
                    }
                }
                current.add(
                    Favourite(
                        entry = Entry(
                            id = id,
                            title = o.optString("title", ""),
                            authors = authors,
                            summary = o.optString("summary", ""),
                            links = links,
                            categories = o.optStringList("categories"),
                            language = o.optString("language", ""),
                            updated = o.optString("updated", "")
                        ),
                        catalogName = o.optString("catalog_name", ""),
                        catalogUrl = o.optString("catalog_url", "")
                    )
                )
                haveIds.add(id)
                addedFavs++
            }
            if (addedFavs > 0) FavouritesStore.save(c, current)
        }

        var positions = 0
        var bookmarks = 0
        root.optJSONObject("reader")?.let { r ->
            val pos = mutableMapOf<String, String>()
            r.optJSONObject("positions")?.let { p ->
                val keys = p.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = p.optString(k, "")
                    if (k.startsWith("pos_") && v.isNotBlank()) {
                        pos[k] = v
                        positions++
                    }
                }
            }
            val marks = mutableMapOf<String, List<ReaderStore.Bookmark>>()
            r.optJSONObject("bookmarks")?.let { b ->
                val keys = b.keys()
                while (keys.hasNext()) {
                    val file = keys.next()
                    val list = mutableListOf<ReaderStore.Bookmark>()
                    b.optJSONArray(file)?.let { a ->
                        for (i in 0 until a.length()) {
                            val mo = a.optJSONObject(i) ?: continue
                            list.add(
                                ReaderStore.Bookmark(
                                    mo.optInt("index", -1),
                                    mo.optString("snippet", "")
                                )
                            )
                        }
                    }
                    val clean = list.filter { it.index >= 0 }
                    if (clean.isNotEmpty()) {
                        marks[file] = clean
                        bookmarks += clean.size
                    }
                }
            }
            val lastRead = r.optJSONObject("last_read")?.let { lr ->
                val file = lr.optString("file", "")
                val name = lr.optString("name", "")
                if (file.isBlank() || name.isBlank()) null else file to name
            }
            if (pos.isNotEmpty() || marks.isNotEmpty() || lastRead != null) {
                ReaderStore.importReader(c, pos, marks, lastRead)
            }
        }

        return ImportResult(addedCatalogs, addedSubs, addedFavs, positions, bookmarks)
    }

    private fun JSONObject.optStringSet(key: String): Set<String> {
        val out = mutableSetOf<String>()
        optJSONArray(key)?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val out = mutableListOf<String>()
        optJSONArray(key)?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out
    }
}
