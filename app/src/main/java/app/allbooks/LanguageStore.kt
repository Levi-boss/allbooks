package app.allbooks

import android.content.Context

/** Вибір мови інтерфейсу: "auto" (за мовою пристрою), "en", "uk" або "ru". */
object LanguageStore {
    private const val PREFS = "allbooks_language"
    private const val KEY = "language"

    fun load(c: Context): String {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AppLanguages.AUTO) ?: AppLanguages.AUTO
    }

    fun save(c: Context, language: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).apply()
    }
}
