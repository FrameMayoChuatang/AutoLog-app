package com.framemayo.autolog.data.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.framemayo.autolog.container
import java.util.concurrent.TimeUnit

class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.trips.purgeSynced(RetentionScheduler.RETENTION_DAYS)
        return Result.success()
    }
}

object RetentionScheduler {
    const val RETENTION_DAYS = 30
    private const val NAME = "trip-retention"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
