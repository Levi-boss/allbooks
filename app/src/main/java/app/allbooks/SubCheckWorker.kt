package app.allbooks

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.allbooks.net.Net
import app.allbooks.opds.OpdsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * Фонова перевірка підписок раз на день: та сама логіка «НОВЕ»,
 * що й на екрані Підписок, плюс системне сповіщення, коли є нові книги.
 * Мітки «НОВЕ» на екрані Підписок працюють як раніше — цей воркер
 * лише оновлює ті самі збережені списки відомих/нових id.
 */
class SubCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsStore.load(context)
        if (!settings.notifyNewBooks) return Result.success()
        val lang = AppLanguages.resolve(LanguageStore.load(context))
        val s = AppLanguages.stringsFor(lang)
        val subs = SubscriptionsStore.load(context)
        if (subs.isEmpty()) return Result.success()
        val updated = withContext(Dispatchers.IO) {
            val client = Net.buildClient(settings)
            subs.map { sub ->
                try {
                    val r = Net.fetchFirst(
                        client, listOf(sub.url),
                        sub.user.ifBlank { null }, sub.pass.ifBlank { null }
                    )
                    val feed = OpdsParser.parse(ByteArrayInputStream(r.bytes), r.url)
                    val ids = feed.entries.map { it.id }.toSet()
                    val newIds = ids - sub.knownIds
                    sub.copy(
                        knownIds = ids,
                        newIds = newIds,
                        lastCheck = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    AppLog.d("SubCheckWorker failed for ${sub.name}: ${e.message}")
                    sub
                }
            }
        }
        SubscriptionsStore.save(context, updated)
        val fresh = updated.filter { it.newIds.isNotEmpty() }
        if (fresh.isNotEmpty()) {
            showNotification(context, s, fresh)
        }
        return Result.success()
    }

    private fun showNotification(context: Context, s: AppStrings, fresh: List<Subscription>) {
        // Без дозволу на сповіщення (Android 13+) — мовчки пропускаємо.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, s.notifyTitle, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val total = fresh.sumOf { it.newIds.size }
        val lines = fresh.take(5).joinToString("\n") { sub ->
            val kind = if (sub.type == "series") s.series else s.author
            "$kind: ${sub.name} — ${sub.newIds.size}"
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${s.notifyTitle}: $total")
            .setContentText(lines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "bookmriya_subscriptions"
        private const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "sub_check"
    }
}

object SubCheckScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SubCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SubCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SubCheckWorker.WORK_NAME)
    }
}
