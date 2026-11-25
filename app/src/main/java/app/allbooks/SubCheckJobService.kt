package app.allbooks

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Фонова перевірка підписок раз на день через системний JobScheduler.
 * Без сторонніх залежностей: WorkManager (2.9.0 і 2.10.0) ламає збірку
 * цього проєкту на етапі залежностей, тому він не використовується.
 * Планується при запуску застосунку, якщо сповіщення ввімкнено.
 */
class SubCheckJobService : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            try {
                SubCheck.run(applicationContext)
            } catch (e: Exception) {
                AppLog.d("SubCheckJob failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

object SubCheckScheduler {
    private const val JOB_ID = 1001

    fun schedule(context: Context) {
        try {
            val service = ComponentName(context, SubCheckJobService::class.java)
            val job = JobInfo.Builder(JOB_ID, service)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(job)
        } catch (e: Exception) {
            // Планування ніколи не повинно роняти запуск застосунку.
            AppLog.d("SubCheck schedule failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
        } catch (e: Exception) {
            AppLog.d("SubCheck cancel failed: ${e.message}")
        }
    }
}
