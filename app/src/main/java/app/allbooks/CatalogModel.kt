package app.allbooks

import android.content.Context
import app.allbooks.opds.Entry
import java.net.URLEncoder

internal data class Catalog(
    val name: String,
    val note: String,
    val urls: List<String>,
    val user: String = "",
    val pass: String = "",
    val noteEn: String = note,
    val noteRu: String = note
) {
    fun noteFor(lang: String): String = when (lang) {
        "en" -> noteEn
        "ru" -> noteRu
        else -> note
    }
}

// Тільки перевірені 25.11.2026 каталоги. Видалено: Gutenberg — весь сайт дає 503,
// ManyBooks — 403, OAPEN — OPDS-адреса дає 404.
internal val CATALOGS = listOf(
    Catalog(
        "Flibusta",
        "Російськомовна та українська бібліотека",
        listOf("http://flibusta.is/opds"),
        noteEn = "Russian- and Ukrainian-language library",
        noteRu = "Русскоязычная и украинская библиотека"
    ),
    Catalog(
        "CoolLib",
        "Російськомовна та українська бібліотека",
        listOf("https://coollib.com/opds"),
        noteEn = "Russian- and Ukrainian-language library",
        noteRu = "Русскоязычная и украинская библиотека"
    ),
    Catalog(
        "unglue.it",
        "Книги під вільними ліцензіями",
        listOf("https://unglue.it/api/opds/"),
        noteEn = "Books under free licenses",
        noteRu = "Книги под свободными лицензиями"
    )
)

internal object CustomStore {
    private const val PREFS = "allbooks"
    private const val KEY = "custom_catalogs"

    fun load(c: Context): List<Catalog> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { line ->
            // Формат: назва|адреса|логін|пароль. Старі записи без логіна/пароля теж читаються.
            val parts = line.split('|')
            if (parts.size < 2 || parts[0].isBlank()) null
            else {
                val url = parts[1]
                Catalog(
                    parts[0], url, listOf(url),
                    user = parts.getOrNull(2) ?: "",
                    // Пароль зберігається зашифрованим (SecretBox); старі
                    // незашифровані значення читаються як є.
                    pass = SecretBox.decrypt(parts.getOrNull(3) ?: "")
                )
            }
        }
    }

    fun save(c: Context, list: List<Catalog>) {
        val raw = list.joinToString("\n") {
            val clean = { s: String -> s.replace("|", " ").replace("\n", " ").trim() }
            clean(it.name) + "|" + clean(it.urls.first()) + "|" + clean(it.user) + "|" + SecretBox.encrypt(it.pass)
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }
}

/** Логін/пароль для каталогів окремо від їх списку: вбудовані каталоги
 *  теж можуть попросити авторизацію (401), а їх редагувати не можна. */
internal object AuthStore {
    private const val PREFS = "allbooks"
    private const val KEY = "catalog_auth"

    fun load(c: Context): Map<String, Pair<String, String>> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        val map = mutableMapOf<String, Pair<String, String>>()
        raw.lines().forEach { line ->
            val parts = line.split('|')
            if (parts.size >= 3 && parts[0].isNotBlank()) {
                // Пароль зберігається зашифрованим (SecretBox); старі
                // незашифровані значення читаються як є.
                map[parts[0]] = parts[1] to SecretBox.decrypt(parts[2])
            }
        }
        return map
    }

    fun save(c: Context, map: Map<String, Pair<String, String>>) {
        val raw = map.entries.joinToString("\n") { (name, up) ->
            val clean = { s: String -> s.replace("|", " ").replace("\n", " ").trim() }
            clean(name) + "|" + clean(up.first) + "|" + SecretBox.encrypt(up.second)
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw).apply()
    }
}

internal fun searchUrlFor(template: String, term: String): String {
    val enc = URLEncoder.encode(term, "UTF-8")
    return template.replace("{searchTerms}", enc).replace("%7BsearchTerms%7D", enc)
}

/** Одна секція результатів глобального пошуку: каталог і його книги. */
internal data class GlobalSection(
    val catalog: Catalog,
    val template: String?,
    val entries: List<Entry>,
    val error: String? = null
)
