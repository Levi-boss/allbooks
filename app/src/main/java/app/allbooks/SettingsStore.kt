package app.allbooks

import android.content.Context
import app.allbooks.net.NetSettings
import app.allbooks.net.ProxyMode

/**
 * Зберігання налаштувань мережі (проксі, DoH) в SharedPreferences.
 * Пароль проксі шифрується ключем з Android Keystore (див. SecretBox).
 */
object SettingsStore {
    private const val PREFS = "allbooks_settings"

    fun load(c: Context): NetSettings {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = try {
            ProxyMode.valueOf(p.getString("proxy_mode", "NONE") ?: "NONE")
        } catch (e: Exception) {
            ProxyMode.NONE
        }
        return NetSettings(
            proxyMode = mode,
            proxyHost = p.getString("proxy_host", "") ?: "",
            proxyPort = p.getInt("proxy_port", 0),
            proxyUser = p.getString("proxy_user", "") ?: "",
            proxyPass = SecretBox.decrypt(p.getString("proxy_pass", "") ?: ""),
            useDoh = p.getBoolean("use_doh", false),
            startCatalog = p.getString("start_catalog", "") ?: "",
            downloadFolder = p.getString("download_folder", "BookMriya") ?: "BookMriya",
            folderChosen = p.getBoolean("folder_chosen", false),
            safFolderUri = p.getString("saf_folder_uri", "") ?: "",
            theme = p.getString("theme", "system") ?: "system",
            notifyNewBooks = p.getBoolean("notify_new_books", true),
            feedGrid = p.getBoolean("feed_grid", false),
            amoled = p.getBoolean("amoled", false)
        )
    }

    fun save(c: Context, s: NetSettings) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("proxy_mode", s.proxyMode.name)
            .putString("proxy_host", s.proxyHost)
            .putInt("proxy_port", s.proxyPort)
            .putString("proxy_user", s.proxyUser)
            .putString("proxy_pass", SecretBox.encrypt(s.proxyPass))
            .putBoolean("use_doh", s.useDoh)
            .putString("start_catalog", s.startCatalog)
            .putString("download_folder", s.downloadFolder)
            .putBoolean("folder_chosen", s.folderChosen)
            .putString("saf_folder_uri", s.safFolderUri)
            .putString("theme", s.theme)
            .putBoolean("notify_new_books", s.notifyNewBooks)
            .putBoolean("feed_grid", s.feedGrid)
            .putBoolean("amoled", s.amoled)
            .apply()
    }
}