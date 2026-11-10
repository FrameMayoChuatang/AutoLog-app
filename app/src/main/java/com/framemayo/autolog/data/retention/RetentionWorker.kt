package com.framemayo.autolog.data.retention

import android.content.Context
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.framemayo.autolog.container
import java.util.concurrent.TimeUnit

class RetentionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val free = StatFs(applicationContext.filesDir.path).availableBytes
        applicationContext.container.trips.purgeSynced(RetentionScheduler.retentionDaysFor(free))
        return Result.success()
    }
}

object RetentionScheduler {
    const val RETENTION_DAYS = 30
    const val LOW_STORAGE_BYTES = 200L * 1024 * 1024

    fun retentionDaysFor(freeBytes: Long): Int = if (freeBytes < LOW_STORAGE_BYTES) 0 else RETENTION_DAYS
    private const val NAME = "trip-retention"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
