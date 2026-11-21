package io.github.axon07.channelbox.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.axon07.channelbox.R
import io.github.axon07.channelbox.container
import java.util.concurrent.TimeUnit

/**
 * Runs one recording. Scheduled recordings are queued with a start delay; the worker
 * runs as a foreground job (with a notification) so Android doesn't stop it after 10 minutes.
 */
class RecordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.failure()
        try {
            setForeground(foregroundInfo(id))
        } catch (e: Exception) {
            // Some Android versions refuse this from the background; record anyway.
        }
        applicationContext.container.recorder.record(id)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getLong(KEY_ID, 0L))

    private fun foregroundInfo(id: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("Channelbox is recording")
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationId = 4200 + (id % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val KEY_ID = "recording_id"
        private const val CHANNEL = "recordings"

        private fun name(id: Long) = "recording-$id"

        fun enqueue(context: Context, id: Long, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<RecordingWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ID to id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, id: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(name(id))
        }
    }
}
