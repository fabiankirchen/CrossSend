package com.crosssend.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.StagedShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ShareHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.action
        val type = intent.type ?: ""

        if (Intent.ACTION_SEND != action && Intent.ACTION_SEND_MULTIPLE != action && Intent.ACTION_VIEW != action) {
            finish()
            return
        }

        val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra("subject")
            ?: intent.clipData?.description?.label)?.toString()?.trim()

        val sharedText = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT))?.toString()

        val multiUris = if (Intent.ACTION_SEND_MULTIPLE == action) {
            val streamList = IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            ).orEmpty()
            val collected = streamList.ifEmpty {
                val clip = intent.clipData
                if (clip != null) {
                    (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
                } else {
                    emptyList()
                }
            }
            collected.distinct()
        } else {
            emptyList()
        }

        val urisToStage: List<Uri> = if (multiUris.isNotEmpty()) {
            val safeUris = multiUris.filter { DocumentProcessor.isSafeSharedUri(this, it) }
            if (safeUris.isEmpty()) {
                Log.w("ShareHandler", "Blocked all unsafe shared URIs in ACTION_SEND_MULTIPLE")
                Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            Log.i("ShareHandler", "Received batch share ($action): type='$type', count=${safeUris.size}")
            safeUris
        } else {
            val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.data
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

            if (streamUri != null && !DocumentProcessor.isSafeSharedUri(this, streamUri)) {
                Log.w("ShareHandler", "Blocked unsafe shared URI (scheme=${streamUri.scheme})")
                Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            Log.i("ShareHandler", "Received share/view ($action): type='$type', hasUri=${streamUri != null}, textLen=${sharedText?.length ?: 0}")
            listOfNotNull(streamUri)
        }

        Toast.makeText(this, getString(R.string.share_receiving_content), Toast.LENGTH_SHORT).show()
        val app = CrossSendApp.instance

        if (urisToStage.isEmpty()) {
            app.applicationScope.launch {
                app.documentProcessor.handleStagedShare(emptyList(), subject, sharedText, type)
            }
            finish()
            return
        }

        // Copy content:// streams into cacheDir before calling finish() so temporary URI grants never expire mid-read.
        lifecycleScope.launch(Dispatchers.IO) {
            val stageDir = File(cacheDir, "incoming_shares/${UUID.randomUUID()}").apply { mkdirs() }
            val stageDirCanonical = stageDir.canonicalPath
            val stagedFiles = mutableListOf<StagedShareFile>()
            var exceededLimit = false

            try {
                for ((index, uri) in urisToStage.withIndex()) {
                    val displayName = resolveDisplayName(uri) ?: "shared_$index"
                    val resolvedMime = contentResolver.getType(uri) ?: type.takeIf { it.isNotBlank() }
                    val safeName = "${index}_${sanitizeTempFileName(displayName)}"
                    val destFile = File(stageDir, safeName)
                    if (!destFile.canonicalPath.startsWith(stageDirCanonical + File.separator)) {
                        continue
                    }

                    val copiedBytes = try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                DocumentProcessor.copyBounded(input, output, DocumentProcessor.MAX_FILE_COPY_BYTES)
                            }
                        } ?: -2L
                    } catch (e: Exception) {
                        Log.w("ShareHandler", "Failed to stage shared URI: ${e.message}")
                        -2L
                    }

                    if (copiedBytes >= 0L) {
                        stagedFiles.add(StagedShareFile(destFile, displayName, resolvedMime))
                    } else {
                        destFile.delete()
                        if (copiedBytes == -1L) {
                            exceededLimit = true
                        }
                    }
                }

                if (stagedFiles.isEmpty()) {
                    stageDir.deleteRecursively()
                    if (!sharedText.isNullOrBlank()) {
                        app.applicationScope.launch {
                            app.documentProcessor.handleStagedShare(emptyList(), subject, sharedText, type)
                        }
                    } else {
                        val errorRes = if (exceededLimit) R.string.proc_error_file_too_large else R.string.proc_error_file_read
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ShareHandlerActivity, getString(errorRes), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    app.applicationScope.launch {
                        app.documentProcessor.handleStagedShare(stagedFiles, subject, sharedText, type)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        finish()
                    }
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            result = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return result?.takeIf { it.isNotBlank() } ?: uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeTempFileName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return sanitized.ifBlank { "file" }.take(80)
    }
}
