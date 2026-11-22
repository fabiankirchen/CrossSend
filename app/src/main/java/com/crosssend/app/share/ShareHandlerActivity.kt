package com.crosssend.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.MainActivity
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.StagedShareFile
import com.crosssend.app.data.local.DomainHostUtils
import com.crosssend.app.data.local.DomainPreferenceEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.settings.AppThemeMode
import com.crosssend.app.data.settings.ShareFlowMode
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.sync.ShareProcessingWorker
import com.crosssend.app.ui.share.ShareBottomSheet
import com.crosssend.app.ui.share.ShareSheetPayload
import com.crosssend.app.ui.theme.CrossSendTheme
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ShareHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
                SafeLogger.w("ShareHandler", "Blocked all unsafe shared URIs in ACTION_SEND_MULTIPLE")
                Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            SafeLogger.d("ShareHandler", "Received batch share ($action): count=${safeUris.size}")
            safeUris
        } else {
            val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.data
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

            if (streamUri != null && !DocumentProcessor.isSafeSharedUri(this, streamUri)) {
                SafeLogger.w("ShareHandler", "Blocked unsafe shared URI (scheme=${streamUri.scheme})")
                Toast.makeText(this, getString(R.string.share_blocked_unsafe_uri), Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            SafeLogger.d("ShareHandler", "Received share/view ($action): hasUri=${streamUri != null}")
            listOfNotNull(streamUri)
        }

        val app = application as CrossSendApp
        val immediateInstant = app.settingsRepository.settings.value.shareFlowMode == ShareFlowMode.INSTANT_BACKGROUND

        if (urisToStage.isEmpty()) {
            if (immediateInstant) {
                Toast.makeText(this, getString(R.string.share_receiving_content), Toast.LENGTH_SHORT).show()
                ShareProcessingWorker.enqueue(
                    context = applicationContext,
                    stagedFiles = emptyList(),
                    subject = subject,
                    sharedText = sharedText,
                    mimeType = type
                )
                finish()
                return
            }
            presentShareSheet(
                app = app,
                stagedFiles = emptyList(),
                stageDir = null,
                subject = subject,
                sharedText = sharedText,
                mimeType = type
            )
            return
        }

        if (immediateInstant) {
            Toast.makeText(this, getString(R.string.share_receiving_content), Toast.LENGTH_SHORT).show()
        }

        // Copy content:// streams into cacheDir before calling finish() so temporary URI grants never expire mid-read.
        lifecycleScope.launch(Dispatchers.IO) {
            val stageDir = File(cacheDir, "incoming_shares/${UUID.randomUUID()}").apply { mkdirs() }
            val stageDirCanonical = stageDir.canonicalPath
            val stagedFiles = mutableListOf<StagedShareFile>()
            var exceededLimit = false

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
                    SafeLogger.w("ShareHandler", "Failed to stage shared URI", e)
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
                    if (immediateInstant) {
                        ShareProcessingWorker.enqueue(
                            context = applicationContext,
                            stagedFiles = emptyList(),
                            subject = subject,
                            sharedText = sharedText,
                            mimeType = type
                        )
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                presentShareSheet(
                                    app = app,
                                    stagedFiles = emptyList(),
                                    stageDir = null,
                                    subject = subject,
                                    sharedText = sharedText,
                                    mimeType = type
                                )
                            }
                        }
                    }
                } else {
                    val errorRes = if (exceededLimit) R.string.proc_error_file_too_large else R.string.proc_error_file_read
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ShareHandlerActivity, getString(errorRes), Toast.LENGTH_SHORT).show()
                        if (!isFinishing && !isDestroyed) finish()
                    }
                }
            } else {
                if (immediateInstant) {
                    ShareProcessingWorker.enqueue(
                        context = applicationContext,
                        stagedFiles = stagedFiles,
                        subject = subject,
                        sharedText = sharedText,
                        mimeType = type
                    )
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            presentShareSheet(
                                app = app,
                                stagedFiles = stagedFiles,
                                stageDir = stageDir,
                                subject = subject,
                                sharedText = sharedText,
                                mimeType = type
                            )
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                stageDir.deleteRecursively()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun presentShareSheet(
        app: CrossSendApp,
        stagedFiles: List<StagedShareFile>,
        stageDir: File?,
        subject: String?,
        sharedText: String?,
        mimeType: String
    ) {
        val rawUrl = extractShareUrl(sharedText, stagedFiles, mimeType)
        val normalizedUrl = rawUrl?.let { raw ->
            val strip = app.settingsRepository.settings.value.stripTrackingParams
            DocumentProcessor.normalizeExternalHttpsUrl(raw, stripTracking = strip)
        }?.takeIf { EInkImageOptimizer.isSafeHttpsExternalUrl(it) }
        val domainHost = normalizedUrl?.let { DomainHostUtils.normalizeHost(it) }
        val payload = buildSharePayload(
            stagedFiles = stagedFiles,
            subject = subject,
            sharedText = sharedText,
            normalizedUrl = normalizedUrl,
            domainHost = domainHost
        )

        setContent {
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle()
            val connectionState by app.wifiManager.connectionState.collectAsStateWithLifecycle()
            val darkTheme = when (settings.themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            var resolvedMode by remember { mutableStateOf(settings.defaultRenderMode) }
            var hasDomainRule by remember { mutableStateOf(false) }

            LaunchedEffect(domainHost, settings.defaultRenderMode) {
                if (domainHost != null) {
                    val savedMode = withContext(Dispatchers.IO) {
                        app.database.domainPreferenceDao().getRenderModeForHost(domainHost)
                    }
                    if (savedMode != null) {
                        resolvedMode = savedMode
                        hasDomainRule = true
                    } else {
                        resolvedMode = settings.defaultRenderMode
                        hasDomainRule = false
                    }
                } else {
                    resolvedMode = settings.defaultRenderMode
                    hasDomainRule = false
                }
            }

            CrossSendTheme(darkTheme = darkTheme) {
                ShareBottomSheet(
                    payload = payload,
                    settings = settings,
                    connectionState = connectionState,
                    initialRenderMode = resolvedMode,
                    hasExistingDomainRule = hasDomainRule,
                    onOpenInAppBrowser = if (normalizedUrl != null) {
                        {
                            lifecycleScope.launch(Dispatchers.IO) {
                                stageDir?.deleteRecursively()
                            }
                            val browserIntent = Intent(this@ShareHandlerActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra(MainActivity.EXTRA_INTERNAL_TOKEN, CrossSendApp.INTERNAL_NOTIFICATION_TOKEN)
                                putExtra(MainActivity.EXTRA_WEBVIEW_URL, normalizedUrl)
                                putExtra(MainActivity.EXTRA_WEBVIEW_TITLE, subject ?: payload.displayTitle)
                            }
                            startActivity(browserIntent)
                            finish()
                        }
                    } else null,
                    onConfirm = { selectedMode, targetFolder, rememberForDomain ->
                        lifecycleScope.launch {
                            if (rememberForDomain && domainHost != null && selectedMode != null) {
                                withContext(Dispatchers.IO) {
                                    app.database.domainPreferenceDao().upsert(
                                        DomainPreferenceEntity(host = domainHost, renderMode = selectedMode)
                                    )
                                }
                            } else if (!rememberForDomain && hasDomainRule && domainHost != null) {
                                withContext(Dispatchers.IO) {
                                    app.database.domainPreferenceDao().deleteByHost(domainHost)
                                }
                            }

                            ShareProcessingWorker.enqueue(
                                context = applicationContext,
                                stagedFiles = stagedFiles,
                                subject = subject,
                                sharedText = sharedText,
                                mimeType = mimeType,
                                renderModeOverride = selectedMode,
                                remoteFolderOverride = targetFolder
                            )

                            val isConnected = connectionState is WifiConnectionState.ConnectedToCrossPointAp ||
                                connectionState is WifiConnectionState.ConnectedOnLocalNetwork
                            val feedbackRes = if (isConnected) {
                                R.string.share_receiving_content
                            } else {
                                R.string.share_queued_for_later
                            }
                            Toast.makeText(this@ShareHandlerActivity, getString(feedbackRes), Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onDismiss = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            stageDir?.deleteRecursively()
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun extractShareUrl(
        sharedText: String?,
        stagedFiles: List<StagedShareFile>,
        mimeType: String
    ): String? {
        val trimmed = sharedText?.trim() ?: return null
        val urlMatch = URL_EXTRACT_REGEX.find(trimmed) ?: return null
        if (stagedFiles.isEmpty()) {
            val isExplicitDoc = mimeType.contains("pdf", ignoreCase = true) ||
                mimeType.contains("epub", ignoreCase = true) ||
                mimeType.contains("adobe.adept", ignoreCase = true)
            if (!isExplicitDoc) return urlMatch.value
        }
        val firstName = stagedFiles.firstOrNull()?.displayName?.lowercase().orEmpty()
        val firstMime = stagedFiles.firstOrNull()?.mimeType.orEmpty().lowercase()
        val isImage = firstName.endsWith(".png") || firstName.endsWith(".jpg") ||
            firstName.endsWith(".jpeg") || firstName.endsWith(".webp") ||
            firstMime.startsWith("image/")
        return if (isImage) urlMatch.value else null
    }

    private fun buildSharePayload(
        stagedFiles: List<StagedShareFile>,
        subject: String?,
        sharedText: String?,
        normalizedUrl: String?,
        domainHost: String?
    ): ShareSheetPayload {
        if (normalizedUrl != null) {
            val title = subject?.takeIf { it.isNotBlank() }
                ?: domainHost
                ?: getString(R.string.share_default_url_title)
            return ShareSheetPayload(
                displayTitle = title,
                subtitle = domainHost ?: normalizedUrl,
                shareUrl = normalizedUrl,
                domainHost = domainHost,
                suggestedDocType = DocumentType.ARTICLE
            )
        }
        if (stagedFiles.isNotEmpty()) {
            val first = stagedFiles.first()
            val lower = first.displayName.lowercase()
            val mime = first.mimeType.orEmpty().lowercase()
            val isSleepImage = (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".bmp") ||
                lower.endsWith(".webp") || mime.startsWith("image/")) && stagedFiles.size == 1
            val isBook = lower.endsWith(".epub") || lower.endsWith(".acsm") ||
                lower.endsWith(".pdf") || mime.contains("epub") || mime.contains("pdf")
            val docType = when {
                isSleepImage -> DocumentType.SLEEP
                isBook -> DocumentType.BOOK
                else -> DocumentType.TEXT
            }
            val title = if (stagedFiles.size == 1) {
                first.displayName
            } else {
                resources.getQuantityString(R.plurals.share_files_count, stagedFiles.size, stagedFiles.size)
            }
            val subtitle = if (stagedFiles.size == 1) {
                first.mimeType?.takeIf { it.isNotBlank() }
            } else {
                first.displayName
            }
            return ShareSheetPayload(
                displayTitle = title,
                subtitle = subtitle,
                shareUrl = null,
                domainHost = null,
                suggestedDocType = docType,
                fileCount = stagedFiles.size
            )
        }
        val cleanSubject = subject?.takeIf { it.isNotBlank() }
        val snippet = sharedText?.trim()?.replace(Regex("\\s+"), " ")?.take(90)
        return ShareSheetPayload(
            displayTitle = cleanSubject ?: getString(R.string.share_default_note_title),
            subtitle = snippet,
            shareUrl = null,
            domainHost = null,
            suggestedDocType = DocumentType.TEXT
        )
    }

    companion object {
        private val URL_EXTRACT_REGEX = "(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)".toRegex()
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
