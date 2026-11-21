package io.github.axon07.channelbox.system

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.axon07.channelbox.container
import java.util.concurrent.TimeUnit

/** Reloads the playlist and TV guide in the background on the interval chosen in Settings. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.current().hasSource) return Result.success()
        return if (c.repository.refresh()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "refresh"

        /** [hours] <= 0 turns automatic reloading off. */
        fun schedule(context: Context, hours: Int) {
            val workManager = WorkManager.getInstance(context)
            if (hours <= 0) {
                workManager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
