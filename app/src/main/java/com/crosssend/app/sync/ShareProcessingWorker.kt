package com.crosssend.app.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.StagedShareFile
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

class ShareProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val textFilePath = inputData.getString(KEY_SHARED_TEXT_FILE_PATH)
        val textFile = textFilePath?.let { File(it) }
        return try {
            val app = (applicationContext as? CrossSendApp) ?: CrossSendApp.instance
            val paths = inputData.getStringArray(KEY_FILE_PATHS).orEmpty()
            val names = inputData.getStringArray(KEY_FILE_NAMES).orEmpty()
            val mimes = inputData.getStringArray(KEY_FILE_MIMES).orEmpty()

            val stagedFiles = paths.mapIndexedNotNull { idx, path ->
                val f = File(path)
                val name = names.getOrNull(idx).orEmpty().ifBlank { f.name }
                val mime = mimes.getOrNull(idx)?.takeIf { it.isNotBlank() }
                if (f.exists()) StagedShareFile(f, name, mime) else null
            }

            val subject = inputData.getString(KEY_SUBJECT)
            val sharedText = if (textFile != null && textFile.exists()) {
                textFile.readText(Charsets.UTF_8)
            } else {
                inputData.getString(KEY_SHARED_TEXT)
            }
            val mimeType = inputData.getString(KEY_MIME_TYPE).orEmpty()
            val renderMode = inputData.getString(KEY_RENDER_MODE)?.let { raw ->
                runCatching { RenderMode.valueOf(raw) }.getOrNull()
            }
            val remoteFolder = inputData.getString(KEY_REMOTE_FOLDER)
            val triggerSync = inputData.getBoolean(KEY_TRIGGER_SYNC, true)

            app.documentProcessor.handleStagedShare(
                stagedFiles = stagedFiles,
                subject = subject,
                sharedText = sharedText,
                mimeType = mimeType,
                renderModeOverride = renderMode,
                remoteFolderOverride = remoteFolder,
                triggerSync = triggerSync
            )
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SafeLogger.e(TAG, "ShareProcessingWorker failed", e)
            Result.failure()
        } finally {
            try {
                textFile?.delete()
            } catch (_: Exception) {}
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, DocumentProcessor.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.worker_share_processing_title))
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
        private const val TAG = "ShareProcessingWorker"
        private const val NOTIFICATION_ID = 9001

        private const val KEY_FILE_PATHS = "file_paths"
        private const val KEY_FILE_NAMES = "file_names"
        private const val KEY_FILE_MIMES = "file_mimes"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_SHARED_TEXT = "shared_text"
        private const val KEY_SHARED_TEXT_FILE_PATH = "shared_text_file_path"
        private const val KEY_MIME_TYPE = "mime_type"
        private const val KEY_RENDER_MODE = "render_mode"
        private const val KEY_REMOTE_FOLDER = "remote_folder"
        private const val KEY_TRIGGER_SYNC = "trigger_sync"

        fun enqueue(
            context: Context,
            stagedFiles: List<StagedShareFile>,
            subject: String?,
            sharedText: String?,
            mimeType: String,
            renderModeOverride: RenderMode? = null,
            remoteFolderOverride: String? = null,
            triggerSync: Boolean = true
        ) {
            val dataBuilder = Data.Builder()
                .putStringArray(KEY_FILE_PATHS, stagedFiles.map { it.file.absolutePath }.toTypedArray())
                .putStringArray(KEY_FILE_NAMES, stagedFiles.map { it.displayName }.toTypedArray())
                .putStringArray(KEY_FILE_MIMES, stagedFiles.map { it.mimeType.orEmpty() }.toTypedArray())
                .putString(KEY_SUBJECT, subject)
                .putString(KEY_MIME_TYPE, mimeType)
                .putString(KEY_RENDER_MODE, renderModeOverride?.name)
                .putString(KEY_REMOTE_FOLDER, remoteFolderOverride)
                .putBoolean(KEY_TRIGGER_SYNC, triggerSync)

            // WorkManager Data rejects payloads > 10 KB; spill large HTML/text shares safely to cacheDir.
            if (sharedText != null && sharedText.toByteArray(Charsets.UTF_8).size > 5120) {
                val shareTextDir = File(context.cacheDir, "incoming_shares").apply { mkdirs() }
                val textFile = File(shareTextDir, "text_${UUID.randomUUID()}.txt")
                textFile.writeText(sharedText, Charsets.UTF_8)
                dataBuilder.putString(KEY_SHARED_TEXT_FILE_PATH, textFile.absolutePath)
            } else {
                dataBuilder.putString(KEY_SHARED_TEXT, sharedText)
            }

            val request = OneTimeWorkRequestBuilder<ShareProcessingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(dataBuilder.build())
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
