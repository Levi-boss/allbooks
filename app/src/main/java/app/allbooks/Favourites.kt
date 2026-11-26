package app.allbooks

import android.content.Context
import app.allbooks.opds.Author
import app.allbooks.opds.Entry
import app.allbooks.opds.Link

/**
 * Закладка: збережена книга (повний запис OPDS) + каталог, звідки вона.
 * Зберігається весь Entry, щоб зі списку закладок відкривалася сторінка
 * книги з кнопками завантаження — окремої сторінки книги в OPDS нема,
 * тому за одним id книгу з сервера не перевантажити.
 */
data class Favourite(
    val entry: Entry,
    val catalogName: String,
    val catalogUrl: String
)

object FavouritesStore {
    private const val PREFS = "allbooks_favourites"
    private const val KEY = "favs"

    // Роздільники: \u001F — поля закладки, \u001E — елементи списків
    // (автори, посилання, жанри), \u001D — поля автора/посилання.
    // У тексті й адресах ці символи не зустрічаються, тому чистимо їх.
    private fun clean(s: String): String = s
        .replace("\u001F", " ").replace("\u001E", " ").replace("\u001D", " ")
        .replace("\n", " ").replace("|", " ").trim()

    fun load(c: Context): List<Favourite> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val p = line.split('\u001F')
            if (p.size < 10 || p[0].isBlank()) return@mapNotNull null
            val authors = p[2].split('\u001E').mapNotNull { a ->
                if (a.isBlank()) null
                else {
                    val ap = a.split('\u001D')
                    Author(ap.getOrNull(0) ?: "", ap.getOrNull(1) ?: "")
                }
            }
            val links = p[4].split('\u001E').mapNotNull { l ->
                if (l.isBlank()) null
                else {
                    val lp = l.split('\u001D')
                    Link(
                        lp.getOrNull(0) ?: "",
                        lp.getOrNull(1) ?: "",
                        lp.getOrNull(2) ?: "",
                        lp.getOrNull(3) ?: ""
                    )
                }
            }
            Favourite(
                entry = Entry(
                    id = p[0],
                    title = p[1],
                    authors = authors,
                    summary = p[3],
                    links = links,
                    categories = p[5].split('\u001E').filter { it.isNotBlank() },
                    language = p[6],
                    updated = p[7]
                ),
                catalogName = p[8],
                catalogUrl = p[9]
            )
        }
    }

    fun save(c: Context, list: List<Favourite>) {
        val raw = list.joinToString("\n") { fav ->
            val e = fav.entry
            listOf(
                clean(e.id),
                clean(e.title),
                e.authors.joinToString("\u001E") {
                    clean(it.name) + "\u001D" + clean(it.uri)
                },
                clean(e.summary),
                e.links.joinToString("\u001E") {
                    clean(it.href) + "\u001D" + clean(it.rel) +
                        "\u001D" + clean(it.type) + "\u001D" + clean(it.title)
                },
                e.categories.joinToString("\u001E") { clean(it) },
                clean(e.language),
                clean(e.updated),
                clean(fav.catalogName),
                clean(fav.catalogUrl)
            ).joinToString("\u001F")
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }

    fun isFavourite(c: Context, id: String): Boolean =
        load(c).any { it.entry.id == id }
}
