package app.allbooks

import android.content.Context

data class Subscription(
    val type: String, // "author" або "series"
    val name: String,
    val url: String,
    val knownIds: Set<String> = emptySet(),
    val newIds: Set<String> = emptySet(),
    val lastCheck: Long = 0L,
    val user: String = "",
    val pass: String = ""
)

object SubscriptionsStore {
    private const val PREFS = "allbooks_subscriptions"
    private const val KEY = "subs"

    fun load(c: Context): List<Subscription> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { line ->
            // Формат: тип|назва|адреса|відомі id через кому|нові id через кому|час перевірки|логін|пароль
            val parts = line.split('|')
            if (parts.size < 3 || parts[2].isBlank()) null
            else Subscription(
                type = parts[0],
                name = parts[1],
                url = parts[2],
                knownIds = parts.getOrNull(3)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                newIds = parts.getOrNull(4)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                lastCheck = parts.getOrNull(5)?.toLongOrNull() ?: 0L,
                user = parts.getOrNull(6) ?: "",
                // Пароль зберігається зашифрованим (SecretBox); старі
                // незашифровані значення читаються як є.
                pass = SecretBox.decrypt(parts.getOrNull(7) ?: "")
            )
        }
    }

    fun save(c: Context, list: List<Subscription>) {
        val clean = { s: String -> s.replace("|", " ").replace("\n", " ").trim() }
        val raw = list.joinToString("\n") {
            listOf(
                it.type, clean(it.name), clean(it.url),
                it.knownIds.joinToString(","),
                it.newIds.joinToString(","),
                it.lastCheck.toString(),
                clean(it.user), SecretBox.encrypt(it.pass)
            ).joinToString("|")
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    fun isSubscribed(c: Context, url: String): Boolean =
        load(c).any { it.url == url }
}
