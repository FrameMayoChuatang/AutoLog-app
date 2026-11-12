package com.framemayo.autolog.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.framemayo.autolog.container
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val done = applicationContext.container.syncRunner.run()
        if (done) {
            SyncScheduler.cancelRetry(applicationContext)
        } else {
            SyncScheduler.scheduleRetry(applicationContext, attempt = inputData.getInt(SyncScheduler.KEY_ATTEMPT, 0) + 1)
        }
        return Result.success()
    }
}

object SyncScheduler {
    private const val NAME = "trip-sync"
    private const val RETRY_NAME = "trip-sync-retry"
    internal const val KEY_ATTEMPT = "attempt"
    private const val BASE_DELAY_SECONDS = 30L
    private const val MAX_DELAY_SECONDS = 300L

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun retryDelaySeconds(attempt: Int): Long {
        var delay = BASE_DELAY_SECONDS
        repeat((attempt - 1).coerceAtLeast(0)) { delay = minOf(delay * 2, MAX_DELAY_SECONDS) }
        return delay
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    internal fun scheduleRetry(context: Context, attempt: Int) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setInitialDelay(retryDelaySeconds(attempt), TimeUnit.SECONDS)
            .setInputData(Data.Builder().putInt(KEY_ATTEMPT, attempt).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun cancelRetry(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(RETRY_NAME)
    }
}
