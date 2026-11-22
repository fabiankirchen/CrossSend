package com.crosssend.app.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.data.model.SyncResult
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.CancellationException

class SyncUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val app = (applicationContext as? CrossSendApp) ?: CrossSendApp.instance
            when (val syncResult = app.syncManager.performSync()) {
                is SyncResult.Success, SyncResult.Queued -> Result.success()
                is SyncResult.Error -> {
                    SafeLogger.w(TAG, "Background upload worker sync error: ${syncResult.message}")
                    if (runAttemptCount < 2) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SafeLogger.e(TAG, "SyncUploadWorker failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, DocumentProcessor.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.worker_sync_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SyncUploadWorker"
        private const val WORK_NAME = "crosssend_sync_upload"
        private const val NOTIFICATION_ID = 9002

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncUploadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
