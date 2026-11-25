package app.allbooks

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Журнал для діагностики: останні ~300 рядків у пам'яті, дублюється в logcat.
 * Сюди НЕ пишуться паролі, логіни і токени — тільки адреси, статуси і помилки.
 * Надіслати журнал можна кнопкою «Поділитися логами» в Налаштуваннях.
 */
object AppLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun d(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
        android.util.Log.d("BookMriya", msg)
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
