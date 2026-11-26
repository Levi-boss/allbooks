package app.allbooks.net

import app.allbooks.AppLog
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

enum class ProxyMode { NONE, HTTP, SOCKS5 }

data class NetSettings(
    val proxyMode: ProxyMode = ProxyMode.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val useDoh: Boolean = false,
    /** Назва каталогу, що відкривається при запуску; порожньо — список каталогів. */
    val startCatalog: String = "",
    /** Підпапка в Завантаженнях для книжок; порожньо — BookMriya. */
    val downloadFolder: String = "BookMriya",
    /** Чи вибирав користувач папку сам (в налаштуваннях або при першому запуску). */
    val folderChosen: Boolean = false,
    /** Адреса папки, вибраної через файловий менеджер (SAF); порожньо — Завантаження. */
    val safFolderUri: String = "",
    /** Тема застосунку: "system" (як у телефоні), "light", "dark". */
    val theme: String = "system",
    /** Сповіщати про нові книги в підписках (перевірка раз на день). */
    val notifyNewBooks: Boolean = true,
    /** Стрічки каталогів — сіткою обкладинок замість списку. */
    val feedGrid: Boolean = false
)

class FetchResult(val url: String, val bytes: ByteArray)

/** Логін і пароль для SOCKS5. Віддаються лише SOCKS-запитам, жодним іншим серверам. */
private object SocksAuth : java.net.Authenticator() {
    @Volatile
    var user: String = ""

    @Volatile
    var pass: String = ""

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val proto = getRequestingProtocol() ?: return null
        if (!proto.startsWith("SOCKS") || user.isEmpty()) return null
        return PasswordAuthentication(user, pass.toCharArray())
    }
}

object Net {

    private data class Key(
        val mode: ProxyMode,
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
        val doh: Boolean
    )

    private val lock = Any()
    private var cachedKey: Key? = null
    private var cachedClient: OkHttpClient? = null

    /** Повертає спільний клієнт; нова збірка лише коли змінились налаштування проксі чи DoH. */
    fun buildClient(s: NetSettings): OkHttpClient {
        val key = Key(s.proxyMode, s.proxyHost, s.proxyPort, s.proxyUser, s.proxyPass, s.useDoh)
        synchronized(lock) {
            val existing = cachedClient
            if (existing != null && key == cachedKey) return existing
            val fresh = create(s)
            cachedKey = key
            cachedClient = fresh
            return fresh
        }
    }

    private fun create(s: NetSettings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        val proxyOn = s.proxyMode != ProxyMode.NONE &&
            s.proxyHost.isNotBlank() && s.proxyPort > 0

        SocksAuth.user = ""
        SocksAuth.pass = ""

        if (proxyOn) {
            val type = if (s.proxyMode == ProxyMode.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
            builder.proxy(
                Proxy(type, InetSocketAddress.createUnresolved(s.proxyHost, s.proxyPort))
            )
            if (s.proxyUser.isNotBlank()) {
                if (s.proxyMode == ProxyMode.HTTP) {
                    builder.proxyAuthenticator(Authenticator { _, response ->
                        response.request.newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(s.proxyUser, s.proxyPass)
                            )
                            .build()
                    })
                } else {
                    SocksAuth.user = s.proxyUser
                    SocksAuth.pass = s.proxyPass
                    java.net.Authenticator.setDefault(SocksAuth)
                }
            }
        }

        if (s.useDoh) {
            val bootstrap = builder.build()
            val doh = DnsOverHttps.Builder()
                .client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1")
                )
                .build()
            builder.dns(doh)
        }

        return builder.build()
    }

    /** Пробує по черзі основну адресу та дзеркала, повертає першу успішну відповідь. */
    fun fetchFirst(
        client: OkHttpClient,
        urls: List<String>,
        user: String? = null,
        pass: String? = null
    ): FetchResult {
        var last: Exception? = null
        for (u in urls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(u)
                    .header("User-Agent", "BookMriya/2.0 (Android)")
                if (!user.isNullOrBlank()) {
                    reqBuilder.header("Authorization", Credentials.basic(user, pass ?: ""))
                }
                val req = reqBuilder.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // Діагностика 401: хто саме просить пароль (заголовок
                        // WWW-Authenticate називає сервер) і яка адреса впала.
                        val challenge = resp.header("WWW-Authenticate")?.let { " [$it]" } ?: ""
                        throw IOException("HTTP ${resp.code}$challenge: $u")
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    AppLog.d("GET $u -> ${resp.code}")
                    return FetchResult(resp.request.url.toString(), bytes)
                }
            } catch (e: Exception) {
                AppLog.d("GET $u failed: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("Немає жодної адреси")
    }
}