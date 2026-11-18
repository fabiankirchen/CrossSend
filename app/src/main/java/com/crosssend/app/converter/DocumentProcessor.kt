package com.crosssend.app.converter

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.crosssend.app.CrossSendApp
import com.crosssend.app.MainActivity
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.local.DomainHostUtils
import com.crosssend.app.data.local.DomainPreferenceEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.RssItem
import com.crosssend.app.util.DateUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class DocumentProcessor(private val context: Context) {
    private val tag = "DocumentProcessor"
    private val notificationCounter = AtomicInteger(1000)
    private val imageOptimizer = EInkImageOptimizer(context)

    companion object {
        const val CHANNEL_ID = "crosssend_progress_silent_v1"
        const val CHANNEL_ALERTS_ID = "crosssend_alerts_silent_v1"
        internal const val MAX_ACSM_BYTES = 2 * 1024 * 1024
        internal const val MAX_HTML_BYTES = 15 * 1024 * 1024
        internal const val MAX_SLEEP_IMAGE_BYTES = 25 * 1024 * 1024
        internal const val MAX_FILE_COPY_BYTES = 150L * 1024 * 1024

        internal fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray? {
            if (maxBytes < 0) return null
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (total > maxBytes - read) {
                    return null
                }
                total += read
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        internal fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
            if (maxBytes < 0L) return -1L
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (total > maxBytes - read) {
                    return -1L
                }
                output.write(buffer, 0, read)
                total += read
            }
            output.flush()
            return total
        }

        @SuppressLint("SdCardPath")
        fun isSafeSharedUri(context: Context, uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "file") {
                val path = uri.path ?: return false
                val canonical = try { File(path).canonicalPath } catch (_: Exception) { return false }
                val appDataDir = try { context.applicationInfo.dataDir?.let { File(it).canonicalPath } } catch (_: Exception) { null }
                val filesDir = try { context.filesDir.canonicalPath } catch (_: Exception) { null }
                val cacheDir = try { context.cacheDir.canonicalPath } catch (_: Exception) { null }
                val devProtectedDir = try { context.applicationInfo.deviceProtectedDataDir?.let { File(it).canonicalPath } } catch (_: Exception) { null }
                val extCacheDir = try { context.externalCacheDir?.canonicalPath } catch (_: Exception) { null }
                val extFilesRoot = try { context.getExternalFilesDir(null)?.parentFile?.canonicalPath } catch (_: Exception) { null }
                if (appDataDir != null && canonical.startsWith(appDataDir)) return false
                if (filesDir != null && canonical.startsWith(filesDir)) return false
                if (cacheDir != null && canonical.startsWith(cacheDir)) return false
                if (devProtectedDir != null && canonical.startsWith(devProtectedDir)) return false
                if (extCacheDir != null && canonical.startsWith(extCacheDir)) return false
                if (extFilesRoot != null && canonical.startsWith(extFilesRoot)) return false
                if (canonical.contains("/data/data/${context.packageName}") ||
                    (canonical.contains("/data/user/") && canonical.contains(context.packageName))) return false
                return true
            }
            if (scheme == "content") {
                val authority = uri.authority?.lowercase() ?: ""
                if (authority.startsWith(context.packageName.lowercase())) return false
                return true
            }
            return false
        }

        fun normalizeExternalHttpsUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return trimmed
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
                !trimmed.contains("://") -> "https://$trimmed"
                else -> trimmed
            }
        }
    }

    fun isSafeSharedUri(uri: Uri): Boolean = Companion.isSafeSharedUri(context, uri)

    init {
        createNotificationChannel()
    }

    fun nextNotificationId(): Int = notificationCounter.incrementAndGet()

    suspend fun handleShare(
        subject: String?,
        sharedText: String?,
        streamUri: Uri?,
        mimeType: String
    ) = withContext(Dispatchers.IO) {
        val notificationId = nextNotificationId()
        try {
            if (streamUri != null && !isSafeSharedUri(streamUri)) {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_internal_access_denied))
                return@withContext
            }

            val trimmedText = sharedText?.trim()
            val urlRegex = "(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)".toRegex()
            val urlMatch = trimmedText?.let { urlRegex.find(it) }

            // Check if streamUri points to an explicit standalone document file (.pdf, .epub, .acsm).
            // Web browsers (Brave, Chrome, etc.) attach screenshot/thumbnail preview images (.png / .jpg)
            // to EXTRA_STREAM or clipData when sharing a web link via the Android system share sheet.
            // These screenshot thumbnails must NEVER override the web article URL!
            val streamFileName = streamUri?.let { getFileNameFromUri(it)?.lowercase() } ?: ""
            val isExplicitDocFile = streamUri != null && (
                streamFileName.endsWith(".pdf") ||
                streamFileName.endsWith(".epub") ||
                streamFileName.endsWith(".acsm") ||
                mimeType.contains("pdf", ignoreCase = true) ||
                mimeType.contains("epub", ignoreCase = true) ||
                mimeType.contains("adobe.adept", ignoreCase = true)
            )

            if (urlMatch != null && !isExplicitDocFile) {
                // Shared text contains a web URL and this is not a standalone PDF/EPUB/ACSM file share.
                // Prioritize processing the web article URL!
                processWebUrl(urlMatch.value, fallbackTitle = subject, notificationId = notificationId)
            } else if (streamUri != null) {
                val isImage = streamFileName.endsWith(".png") || streamFileName.endsWith(".jpg") ||
                              streamFileName.endsWith(".jpeg") || streamFileName.endsWith(".webp") ||
                              mimeType.startsWith("image/", ignoreCase = true) ||
                              streamUri.toString().contains("screenshot", ignoreCase = true)

                if (isImage && urlMatch != null) {
                    processWebUrl(urlMatch.value, fallbackTitle = subject, notificationId = notificationId)
                } else {
                    processFileUri(streamUri, mimeType, notificationId)
                }
            } else if (!trimmedText.isNullOrBlank()) {
                if (urlMatch != null && (trimmedText == urlMatch.value || trimmedText.length < urlMatch.value.length + 300 || trimmedText.lines().size <= 3)) {
                    processWebUrl(urlMatch.value, fallbackTitle = subject, notificationId = notificationId)
                } else if (isHtmlContent(trimmedText) || mimeType.contains("html", ignoreCase = true)) {
                    processHtmlContent(trimmedText, baseUrl = null, subject = subject, notificationId = notificationId)
                } else {
                    processEmailOrText(subject, sharedText, notificationId)
                }
            } else {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_no_shareable_content))
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "handleShare failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
        }
    }

    suspend fun resolveRenderModeForUrl(url: String?): RenderMode {
        val host = DomainHostUtils.normalizeHost(url) ?: return RenderMode.READER
        return CrossSendApp.instance.database.domainPreferenceDao().getRenderModeForHost(host) ?: RenderMode.READER
    }

    private suspend fun upsertArticleDocument(
        title: String,
        author: String?,
        sourceUrl: String?,
        targetFile: File,
        fileName: String,
        excerpt: String?,
        renderMode: RenderMode,
        publishedTimestamp: Long = System.currentTimeMillis(),
        existingById: DocumentEntity? = null
    ) {
        val app = CrossSendApp.instance
        val remotePath = "/crosssend/articles/$fileName"
        val existing = existingById ?: sourceUrl?.let { app.database.documentDao().findBySourceUrl(it) }
        if (existing != null) {
            if (existing.localPath != targetFile.absolutePath) {
                try {
                    File(existing.localPath).delete()
                    com.crosssend.app.util.EpubCoverLoader.deleteSidecarCoverForDocument(context, existing.fileName)
                } catch (e: Exception) {
                    Log.w(tag, "Konnte alte Datei nicht löschen: ${existing.localPath}", e)
                }
            }
            val obsoleteRemote = if (existing.syncStatus == SyncStatus.SYNCED && existing.remotePath != remotePath) {
                existing.remotePath
            } else {
                existing.pendingDeleteRemotePath
            }
            val effectiveCreatedAt = if (existing.syncStatus == SyncStatus.ARCHIVED || existing.archivedAt != null) {
                maxOf(publishedTimestamp, (existing.archivedAt ?: 0L) + 1000L, System.currentTimeMillis())
            } else {
                publishedTimestamp
            }
            app.database.documentDao().update(
                existing.copy(
                    title = title,
                    author = author,
                    localPath = targetFile.absolutePath,
                    fileName = fileName,
                    remotePath = remotePath,
                    fileSizeBytes = targetFile.length(),
                    createdAt = effectiveCreatedAt,
                    archivedAt = null,
                    syncStatus = SyncStatus.PENDING_UPLOAD,
                    syncError = null,
                    excerpt = excerpt ?: existing.excerpt,
                    renderMode = renderMode,
                    pendingDeleteRemotePath = obsoleteRemote
                )
            )
        } else {
            val doc = DocumentEntity(
                title = title,
                author = author,
                sourceUrl = sourceUrl,
                type = DocumentType.ARTICLE,
                localPath = targetFile.absolutePath,
                fileName = fileName,
                remotePath = remotePath,
                fileSizeBytes = targetFile.length(),
                createdAt = publishedTimestamp,
                syncStatus = SyncStatus.PENDING_UPLOAD,
                excerpt = excerpt,
                renderMode = renderMode
            )
            app.database.documentDao().insert(doc)
        }
    }

    suspend fun processWebUrlOriginalLayout(
        url: String,
        fallbackTitle: String? = null,
        author: String? = null,
        publishedDate: Date? = null,
        existingDoc: DocumentEntity? = null,
        notificationId: Int = nextNotificationId(),
        triggerSync: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        val progressTitle = fallbackTitle ?: existingDoc?.title ?: "CrossSend"
        showProgressNotification(notificationId, progressTitle, context.getString(R.string.proc_progress_rendering_layout), indeterminate = true)

        try {
            require(EInkImageOptimizer.isSafeExternalImageUrl(url)) { "Invalid or internal URL blocked: $url" }
            val slugTitle = app.articleExtractor.extractTitleFromUrlSlug(url)
            val initialTitle = fallbackTitle?.takeIf { it.isNotBlank() }
                ?: existingDoc?.title?.takeIf { it.isNotBlank() }
                ?: slugTitle
                ?: context.getString(R.string.proc_default_web_article_title)

            val articlesDir = File(app.libraryDir, "articles").apply { mkdirs() }
            val tempXtchFile = File.createTempFile("xtch_build_", ".xtch", context.cacheDir)

            val renderRes = try {
                WebPageXtchRenderer.renderUrlToXtch(
                    context = context,
                    url = url,
                    outputFile = tempXtchFile,
                    title = initialTitle,
                    author = author ?: existingDoc?.author ?: DomainHostUtils.normalizeHost(url)
                )
            } catch (t: Throwable) {
                tempXtchFile.delete()
                throw t
            }
            if (renderRes == null || !tempXtchFile.exists()) {
                tempXtchFile.delete()
                showErrorNotification(notificationId, context.getString(R.string.proc_error_xtch_failed))
                return@withContext false
            }

            val effectiveDate = publishedDate
                ?: renderRes.publishedDate
                ?: extractTimestampFromUrl(url)
                ?: Date()
            val timestamp = DateUtils.formatForFilename(effectiveDate)

            val effectiveTitle = app.articleExtractor.sanitizeDocumentTitle(
                fallbackTitle?.takeIf { it.isNotBlank() }
                    ?: existingDoc?.title?.takeIf { it.isNotBlank() }
                    ?: renderRes.title?.takeIf { it.isNotBlank() }
                    ?: initialTitle
            )
            val finalFileName = "${timestamp}_${sanitizeFilename(effectiveTitle)}.xtch"
            val finalFile = ensureInsideDirectory(File(articlesDir, finalFileName), articlesDir)
            try {
                tempXtchFile.copyTo(finalFile, overwrite = true)
            } finally {
                tempXtchFile.delete()
            }

            val preservedCover = com.crosssend.app.util.EpubCoverLoader.preserveOrExtractCoverForNewFileName(
                context = context,
                previousDoc = existingDoc,
                newDocFileName = finalFileName
            )
            if (!preservedCover) {
                val leadUrl = renderRes.leadImageUrl
                val optCover = if (!leadUrl.isNullOrBlank()) {
                    try {
                        imageOptimizer.optimizeImage(leadUrl, index = 0, isCover = true)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                val downloadedCover = optCover?.colorCoverFile ?: optCover?.localFile
                if (downloadedCover != null && downloadedCover.exists()) {
                    com.crosssend.app.util.EpubCoverLoader.saveColorCoverForDocument(context, finalFileName, downloadedCover)
                }
                optCover?.localFile?.delete()
                optCover?.colorCoverFile?.delete()
            }
            renderRes.colorCoverFile?.delete()

            upsertArticleDocument(
                title = effectiveTitle,
                author = author ?: existingDoc?.author ?: DomainHostUtils.normalizeHost(url),
                sourceUrl = url,
                targetFile = finalFile,
                fileName = finalFileName,
                excerpt = renderRes.excerpt,
                renderMode = RenderMode.ORIGINAL_LAYOUT,
                publishedTimestamp = effectiveDate.time,
                existingById = existingDoc
            )

            if (triggerSync && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
                showProgressNotification(notificationId, effectiveTitle, context.getString(R.string.proc_progress_transferring), indeterminate = true)
                app.syncManager.performSync()
                showSuccessNotification(notificationId, effectiveTitle, context.getString(R.string.proc_success_transferred))
            } else if (triggerSync) {
                showSuccessNotification(notificationId, effectiveTitle, context.getString(R.string.proc_success_saved_for_sync))
            } else {
                dismissNotification(notificationId)
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processWebUrlOriginalLayout failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
            false
        }
    }

    suspend fun switchDocumentRenderMode(documentId: Long, newMode: RenderMode): Boolean = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        val doc = app.database.documentDao().getById(documentId) ?: return@withContext false
        val url = doc.sourceUrl?.takeIf { EInkImageOptimizer.isSafeExternalImageUrl(it) } ?: return@withContext false

        // Persist domain preference for all future articles from this host
        DomainHostUtils.normalizeHost(url)?.let { host ->
            app.database.domainPreferenceDao().upsert(
                DomainPreferenceEntity(host = host, renderMode = newMode)
            )
        }

        if (doc.renderMode == newMode && File(doc.localPath).exists()) {
            return@withContext true
        }

        val notifId = nextNotificationId()
        return@withContext if (newMode == RenderMode.ORIGINAL_LAYOUT) {
            processWebUrlOriginalLayout(
                url = url,
                fallbackTitle = doc.title,
                author = doc.author,
                existingDoc = doc,
                notificationId = notifId,
                triggerSync = true
            )
        } else {
            showProgressNotification(notifId, doc.title, context.getString(R.string.proc_progress_reading_article), indeterminate = true)
            try {
                val (article, rawHtml) = app.articleExtractor.extractFromUrlWithRaw(url)
                val finalTitle = doc.title.ifBlank { article.title }
                processExtractedArticle(
                    article = article.copy(title = finalTitle),
                    notificationId = notifId,
                    rawInput = rawHtml,
                    sourceType = "MODE_SWITCH"
                )
                true
            } catch (paywallEx: PaywallOrLoginRequiredException) {
                dismissNotification(notifId)
                app.webArticleLoginRequest.value = com.crosssend.app.WebArticleLoginRequest(url, doc.title)
                false
            } catch (e: Exception) {
                if (e is CancellationException) {
                    dismissNotification(notifId)
                    throw e
                }
                Log.e(tag, "switchDocumentRenderMode to READER failed", e)
                showErrorNotification(notifId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
                false
            }
        }
    }

    suspend fun rerenderOriginalLayoutDocumentsForDeviceChange(): Int = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        val docs = app.database.documentDao().getOriginalLayoutDocuments()
        if (docs.isEmpty()) return@withContext 0

        var updatedCount = 0
        for (doc in docs) {
            val url = doc.sourceUrl ?: continue
            if (!EInkImageOptimizer.isSafeExternalImageUrl(url)) continue
            val ok = processWebUrlOriginalLayout(
                url = url,
                fallbackTitle = doc.title,
                author = doc.author,
                existingDoc = doc,
                notificationId = nextNotificationId(),
                triggerSync = false
            )
            if (ok) updatedCount++
        }

        if (updatedCount > 0 && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
            app.syncManager.performSync()
        }
        updatedCount
    }

    suspend fun processWebUrl(
        url: String,
        fallbackTitle: String? = null,
        notificationId: Int = nextNotificationId(),
        triggerSync: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        try {
            require(EInkImageOptimizer.isSafeExternalImageUrl(url)) { "Invalid or internal URL blocked: $url" }
            if (resolveRenderModeForUrl(url) == RenderMode.ORIGINAL_LAYOUT) {
                return@withContext processWebUrlOriginalLayout(
                    url = url,
                    fallbackTitle = fallbackTitle,
                    author = null,
                    publishedDate = null,
                    notificationId = notificationId,
                    triggerSync = triggerSync
                )
            }
            showProgressNotification(notificationId, "CrossSend", context.getString(R.string.proc_progress_reading_article), indeterminate = true)

            val (article, rawHtml) = app.articleExtractor.extractFromUrlWithRaw(url)
            val cleanedArticleTitle = app.articleExtractor.cleanTitle(article.title)
            val cleanedFallbackTitle = fallbackTitle?.let { app.articleExtractor.cleanTitle(it) }

            val rawEffectiveTitle = when {
                !cleanedFallbackTitle.isNullOrBlank() && !app.articleExtractor.isGenericTitle(cleanedFallbackTitle) -> cleanedFallbackTitle
                cleanedArticleTitle.isNotBlank() && cleanedArticleTitle != "Unbenannter Artikel" && cleanedArticleTitle != "Untitled Article" && !app.articleExtractor.isGenericTitle(cleanedArticleTitle) -> cleanedArticleTitle
                else -> app.articleExtractor.extractTitleFromUrlSlug(url) ?: cleanedFallbackTitle ?: context.getString(R.string.proc_default_web_article_title)
            }
            val effectiveTitle = app.articleExtractor.sanitizeDocumentTitle(rawEffectiveTitle)
            val finalArticle = article.copy(title = effectiveTitle)
            processExtractedArticle(
                article = finalArticle,
                notificationId = notificationId,
                rawInput = rawHtml,
                sourceType = "WEB_URL",
                inputSubject = fallbackTitle,
                triggerSync = triggerSync
            )
        } catch (paywallEx: PaywallOrLoginRequiredException) {
            Log.i(tag, "Paywall / Login detected for $url: ${paywallEx.message}")
            showPaywallLoginNotification(notificationId, url, paywallEx.articleTitle ?: fallbackTitle)
            app.webArticleLoginRequest.value = com.crosssend.app.WebArticleLoginRequest(url, paywallEx.articleTitle ?: fallbackTitle)
            false
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processWebUrl failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
            false
        }
    }

    suspend fun processHtmlContent(
        html: String,
        baseUrl: String? = null,
        subject: String? = null,
        notificationId: Int = nextNotificationId()
    ) = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        showProgressNotification(notificationId, "CrossSend", context.getString(R.string.proc_progress_reading_article), indeterminate = true)

        try {
            val effectiveBaseUrl = baseUrl ?: inferBaseUrlFromHtml(html)
            val article = app.articleExtractor.extractFromHtml(html, effectiveBaseUrl)

            val parsedDoc = org.jsoup.Jsoup.parse(html)
            val webVersionSlug = app.articleExtractor.extractTitleFromNewsletterWebLink(parsedDoc)
            val kickerCandidate = app.articleExtractor.extractKickerFromDocument(parsedDoc)

            // Look for Subject: / Betreff: in the top of the HTML
            var htmlHeaderSubject: String? = null
            for (el in parsedDoc.select("p, div, td, b, strong, span").take(15)) {
                val t = el.ownText().trim().ifBlank { el.text().trim() }
                val lower = t.lowercase()
                if (lower.startsWith("subject:") || lower.startsWith("betreff:")) {
                    val candidate = app.articleExtractor.cleanTitle(t.substringAfter(':').trim())
                    if (candidate.isNotBlank() && !app.articleExtractor.isGenericTitle(candidate)) {
                        htmlHeaderSubject = candidate
                        break
                    }
                }
            }

            // Subject passed from the email share menu / intent
            val cleanedSubject = subject?.let { app.articleExtractor.cleanTitle(it) }?.takeIf { it.isNotBlank() }
            val nonGenericSubject = cleanedSubject?.takeIf { !app.articleExtractor.isGenericTitle(it) }

            val cleanedArticleTitle = app.articleExtractor.cleanTitle(article.title).takeIf {
                it.isNotBlank() && it != "Unbenannter Artikel" && it != "Untitled Article" && !app.articleExtractor.isGenericTitle(it) && it.length <= 100
            }
            val urlSlug = effectiveBaseUrl?.let { app.articleExtractor.extractTitleFromUrlSlug(it) }

            // Priority:
            // 1. Email subject from share menu / intent (e.g. "Auf die Almancis kommt es an")
            // 2. HTML header subject (e.g. "Subject: ...")
            // 3. Web version link slug (e.g. "Die Almancis Kommt Es An")
            // 4. Kicker from document (e.g. "Thema des Tages: Auf die Almancis kommt es an")
            // 5. Any subject provided by the user/app even if generic (e.g. "Thema des Tages")
            // 6. Cleaned article title from DOM
            // 7. URL slug
            // 8. Fallback "Web-Artikel"
            val rawEffectiveTitle = when {
                nonGenericSubject != null -> nonGenericSubject
                htmlHeaderSubject != null -> htmlHeaderSubject
                webVersionSlug != null -> webVersionSlug
                kickerCandidate != null -> kickerCandidate
                cleanedSubject != null -> cleanedSubject
                cleanedArticleTitle != null -> cleanedArticleTitle
                urlSlug != null -> urlSlug
                else -> context.getString(R.string.proc_default_web_article_title)
            }
            val effectiveTitle = app.articleExtractor.sanitizeDocumentTitle(rawEffectiveTitle)
            val finalArticle = article.copy(title = effectiveTitle)
            processExtractedArticle(
                article = finalArticle,
                notificationId = notificationId,
                rawInput = html,
                sourceType = "HTML_SHARE",
                inputSubject = subject
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processHtmlContent failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
        }
    }

    suspend fun processExtractedArticle(
        article: ExtractedArticle,
        notificationId: Int = nextNotificationId(),
        rawInput: String? = null,
        sourceType: String = "WEB_ARTICLE",
        inputSubject: String? = null,
        triggerSync: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        try {
            val finalContentHtml = article.contentHtml

            showProgressNotification(notificationId, article.title, context.getString(R.string.proc_progress_optimizing_images), indeterminate = true)

            // 1. Optimize Cover image
            val coverImage = article.leadImageUrl?.let {
                app.imageOptimizer.optimizeImage(it, 0, isCover = true)
            }

            // 2. Optimize inline images
            val inlineImages = article.imageUrls.take(12).mapIndexedNotNull { index, imgUrl ->
                app.imageOptimizer.optimizeImage(imgUrl, index + 1, isCover = false)
            }

            showProgressNotification(notificationId, article.title, context.getString(R.string.proc_progress_creating_epub), indeterminate = true)
            val effectiveDate = article.publishedDate
                ?: extractTimestampFromUrl(article.sourceUrl)
                ?: Date()
            val timestamp = DateUtils.formatForFilename(effectiveDate)
            val cleanTitle = sanitizeFilename(article.title)
            val fileName = "${timestamp}_${cleanTitle}.epub"

            val articlesDir = File(app.libraryDir, "articles").apply { mkdirs() }
            val targetEpubFile = ensureInsideDirectory(File(articlesDir, fileName), articlesDir)

            val built = app.epubBuilder.buildEpub(
                title = article.title,
                subtitle = article.subtitle,
                author = article.author ?: article.siteName,
                sourceUrl = article.sourceUrl,
                contentHtml = finalContentHtml,
                coverImage = coverImage,
                inlineImages = inlineImages,
                outputFile = targetEpubFile,
                wordCount = article.wordCount,
                readingTimeMinutes = article.readingTimeMinutes,
                publishedDate = effectiveDate
            )

            if (built) {
                com.crosssend.app.util.EpubCoverLoader.saveColorCoverForDocument(context, fileName, coverImage?.colorCoverFile)
            }
            coverImage?.localFile?.delete()
            coverImage?.colorCoverFile?.delete()
            inlineImages.forEach { it.localFile.delete() }

            val excerpt = article.subtitle?.takeIf { it.isNotBlank() }?.take(300)
                ?: org.jsoup.Jsoup.parse(finalContentHtml).text().take(300).takeIf { it.isNotBlank() }

            if (built) {
                upsertArticleDocument(
                    title = article.title,
                    author = article.author ?: article.siteName,
                    sourceUrl = article.sourceUrl,
                    targetFile = targetEpubFile,
                    fileName = fileName,
                    excerpt = excerpt,
                    renderMode = RenderMode.READER,
                    publishedTimestamp = effectiveDate.time
                )

                // Record debug dump if raw input is available
                if (!rawInput.isNullOrBlank()) {
                    com.crosssend.app.util.DebugDumpManager.dumpItem(
                        context = context,
                        sourceType = sourceType,
                        sourceUrl = article.sourceUrl,
                        inputSubject = inputSubject ?: article.title,
                        rawInput = rawInput,
                        article = article,
                        epubFile = targetEpubFile
                    )
                }

                if (triggerSync && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
                    showProgressNotification(notificationId, article.title, context.getString(R.string.proc_progress_transferring), indeterminate = true)
                    app.syncManager.performSync()
                    showSuccessNotification(notificationId, article.title, context.getString(R.string.proc_success_transferred))
                } else if (triggerSync) {
                    showSuccessNotification(notificationId, article.title, context.getString(R.string.proc_success_saved_for_sync))
                } else {
                    dismissNotification(notificationId)
                }
                true
            } else {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_epub_failed))
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processExtractedArticle failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
            false
        }
    }

    suspend fun processEmailOrText(subject: String?, text: String, notificationId: Int = nextNotificationId()) = withContext(Dispatchers.IO) {
        val trimmedText = text.trim()
        if (isHtmlContent(trimmedText)) {
            processHtmlContent(trimmedText, baseUrl = null, subject = subject, notificationId = notificationId)
            return@withContext
        }
        val app = CrossSendApp.instance
        val lines = text.lines()
        var extractedSubject: String? = subject?.let { app.articleExtractor.cleanTitle(it) }?.takeIf {
            it.isNotBlank() && !app.articleExtractor.isGenericTitle(it)
        }
        var extractedAuthor: String? = null
        var extractedDate: Date? = null

        val headerKeys = listOf(
            "subject:", "betreff:", "from:", "von:", "date:", "datum:",
            "to:", "an:", "cc:", "bcc:", "reply-to:", "sent:", "gesendet:"
        )

        val editorialRoles = listOf(
            "chefredakteur", "chefredakteurin", "chefredaktion", "redakteur", "redakteurin",
            "redaktion", "autor", "autorin", "editor", "editor-in-chief", "reporter",
            "journalist", "journalistin", "korrespondent", "korrespondentin", "herausgeber",
            "herausgeberin", "publisher", "kolumnist", "kolumnistin", "columnist", "gastautor"
        )

        val nonBlankLines = lines.map { it.trim() }.filter { it.isNotBlank() }

        // Heuristic: Check if the first line is an Author Name and second line is an Editorial Role
        // e.g. "Anette Dowideit\nChefredakteurin"
        var skipFirstTwoLinesForAuthor = false
        if (extractedAuthor == null && nonBlankLines.size >= 2) {
            val candidateAuthor = nonBlankLines[0]
            val candidateRole = nonBlankLines[1].lowercase()
            val isAuthorWords = candidateAuthor.split("\\s+".toRegex()).size in 2..4 &&
                    !candidateAuthor.contains(":") && !candidateAuthor.contains("http") && !candidateAuthor.startsWith("[")
            val isEditorialRole = editorialRoles.any { candidateRole == it || candidateRole.startsWith("$it ") || candidateRole.startsWith("$it,") }
            if (isAuthorWords && isEditorialRole) {
                extractedAuthor = candidateAuthor
                skipFirstTwoLinesForAuthor = true
            }
        }

        val bodyLines = mutableListOf<String>()
        var inHeaderSection = true
        var nonBlankCount = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (inHeaderSection && (extractedSubject != null || extractedAuthor != null || extractedDate != null)) {
                    inHeaderSection = false
                    continue
                }
                bodyLines.add("")
                continue
            }

            nonBlankCount++
            if (skipFirstTwoLinesForAuthor && nonBlankCount <= 2) {
                continue
            }

            val lower = trimmed.lowercase()

            // Standard email header fields
            if (inHeaderSection && headerKeys.any { lower.startsWith(it) }) {
                when {
                    lower.startsWith("subject:") || lower.startsWith("betreff:") -> {
                        val value = trimmed.substringAfter(':').trim()
                        val cleaned = app.articleExtractor.cleanTitle(value)
                        if (extractedSubject == null || app.articleExtractor.isGenericTitle(extractedSubject)) {
                            if (cleaned.isNotBlank() && !app.articleExtractor.isGenericTitle(cleaned)) {
                                extractedSubject = cleaned
                            }
                        }
                    }
                    lower.startsWith("from:") || lower.startsWith("von:") -> {
                        val value = trimmed.substringAfter(':').trim()
                        val cleanAuthor = value.substringBefore('<').trim().takeIf { it.isNotBlank() } ?: value
                        if (extractedAuthor.isNullOrBlank()) {
                            extractedAuthor = cleanAuthor.removeSurrounding("\"")
                        }
                    }
                    lower.startsWith("date:") || lower.startsWith("datum:") || lower.startsWith("sent:") || lower.startsWith("gesendet:") -> {
                        val value = trimmed.substringAfter(':').trim()
                        if (extractedDate == null) {
                            extractedDate = DateUtils.parseDate(value)
                        }
                    }
                }
                continue
            }

            // Check if this line is an in-browser / audio / webversion link
            val isWebVersionLink = lower.contains("browser lesen") ||
                    lower.contains("online lesen") ||
                    lower.contains("webversion") ||
                    lower.contains("im web lesen") ||
                    lower.contains("audioversion") ||
                    lower.contains("view in browser") ||
                    lower.contains("read online")

            if (isWebVersionLink) {
                val urlMatch = "(https?://[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]+)".toRegex().find(trimmed)
                if (urlMatch != null) {
                    val linkUrl = urlMatch.value.trimEnd(')', ']', '>', '.', ',', ';')
                    if (extractedSubject == null || app.articleExtractor.isGenericTitle(extractedSubject)) {
                        val slugTitle = app.articleExtractor.extractTitleFromUrlSlug(linkUrl)
                        if (slugTitle != null && !app.articleExtractor.isGenericTitle(slugTitle)) {
                            extractedSubject = slugTitle
                        }
                    }
                    if (extractedDate == null) {
                        extractedDate = extractTimestampFromUrl(linkUrl)
                    }
                }
                continue
            }

            inHeaderSection = false

            // Drop line if it's an exact duplicate of the subject/title at the top of the body
            val isDuplicateTitle = extractedSubject != null && (
                trimmed.equals(extractedSubject, ignoreCase = true) ||
                trimmed.equals("Re: $extractedSubject", ignoreCase = true) ||
                trimmed.equals("Fwd: $extractedSubject", ignoreCase = true) ||
                trimmed.equals("WG: $extractedSubject", ignoreCase = true) ||
                trimmed.equals("AW: $extractedSubject", ignoreCase = true)
            )

            if (isDuplicateTitle && bodyLines.none { it.isNotBlank() }) {
                continue
            }

            bodyLines.add(trimmed)
        }

        // Determine title: Avoid salutations or rubrics like "Thema des Tages" becoming book titles
        val salutationPrefixes = listOf(
            "liebe ", "lieber ", "sevgili ", "sehr geehrte", "hallo",
            "guten tag", "guten morgen", "guten abend", "dear ", "hi ", "hello "
        )

        var titleCandidate = extractedSubject?.takeIf { it.isNotBlank() && !app.articleExtractor.isGenericTitle(it) }

        // If no subject candidate yet, check if there's a kicker line in bodyLines like "Thema des Tages: Auf die Almancis kommt es an"
        if (titleCandidate == null) {
            val kickerLine = bodyLines.firstOrNull { line ->
                val l = line.trim()
                app.articleExtractor.hasKickerPrefix(l)
            }
            if (kickerLine != null) {
                val cleaned = app.articleExtractor.cleanTitle(kickerLine)
                if (cleaned.isNotBlank() && !app.articleExtractor.isGenericTitle(cleaned)) {
                    titleCandidate = cleaned
                }
            }
        }

        if (titleCandidate == null) {
            val firstSignificant = bodyLines.firstOrNull { line ->
                val l = line.lowercase().trim()
                line.isNotBlank() &&
                line.length <= 120 &&
                !app.articleExtractor.isGenericTitle(line) &&
                salutationPrefixes.none { l.startsWith(it) } &&
                !l.contains("cookie")
            }
            titleCandidate = firstSignificant?.let { app.articleExtractor.cleanTitle(it) } ?: bodyLines.firstOrNull { it.isNotBlank() && it.length <= 120 }
        }

        val rawTitle = app.articleExtractor.sanitizeDocumentTitle(titleCandidate)
        val isEmail = !subject.isNullOrBlank() || extractedAuthor != null || extractedDate != null
        val effectiveDate = extractedDate ?: Date()
        val timestamp = DateUtils.formatForFilename(effectiveDate)
        val cleanTitle = sanitizeFilename(rawTitle).take(50)
        val fileName = "${timestamp}_${cleanTitle}.epub"

        showProgressNotification(
            notificationId = notificationId,
            title = rawTitle,
            message = if (isEmail) context.getString(R.string.proc_progress_processing_email) else context.getString(R.string.proc_progress_creating_article),
            indeterminate = true
        )

        val finalContentHtml = buildFallbackParagraphHtml(bodyLines, rawTitle)

        val wordCount = text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val readingTime = maxOf(1, (wordCount / 210.0).toInt())

        val targetDir = File(app.libraryDir, "articles").apply { mkdirs() }
        val targetFile = ensureInsideDirectory(File(targetDir, fileName), targetDir)
        val finalAuthor = extractedAuthor

        val built = app.epubBuilder.buildEpub(
            title = rawTitle,
            author = finalAuthor,
            sourceUrl = null,
            contentHtml = finalContentHtml,
            coverImage = null,
            inlineImages = emptyList(),
            outputFile = targetFile,
            wordCount = wordCount,
            readingTimeMinutes = readingTime,
            publishedDate = extractedDate
        )

        if (built) {
            val excerpt = bodyLines.filter { it.isNotBlank() }.joinToString(" ").take(300).takeIf { it.isNotBlank() }
            val doc = DocumentEntity(
                title = rawTitle,
                author = finalAuthor,
                sourceUrl = null,
                type = DocumentType.ARTICLE,
                localPath = targetFile.absolutePath,
                fileName = fileName,
                remotePath = "/crosssend/articles/$fileName",
                fileSizeBytes = targetFile.length(),
                createdAt = effectiveDate.time,
                syncStatus = SyncStatus.PENDING_UPLOAD,
                excerpt = excerpt
            )
            app.database.documentDao().insert(doc)

            // Record debug dump for email/text
            val mockArticle = ExtractedArticle(
                title = rawTitle,
                author = finalAuthor,
                contentHtml = finalContentHtml,
                sourceUrl = "",
                wordCount = wordCount,
                readingTimeMinutes = readingTime,
                publishedDate = effectiveDate
            )
            com.crosssend.app.util.DebugDumpManager.dumpItem(
                context = context,
                sourceType = if (isEmail) "EMAIL_SHARE" else "TEXT_SHARE",
                sourceUrl = null,
                inputSubject = subject,
                rawInput = text,
                article = mockArticle,
                epubFile = targetFile
            )

            if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                app.syncManager.performSync()
                showSuccessNotification(notificationId, rawTitle, context.getString(R.string.proc_success_transferred))
            } else {
                showSuccessNotification(notificationId, rawTitle, context.getString(R.string.proc_success_saved_articles))
            }
        } else {
            showErrorNotification(notificationId, context.getString(R.string.proc_error_epub_failed))
        }
    }

    suspend fun processPdfUri(uri: Uri, rawFileName: String? = null, notificationId: Int = nextNotificationId()) = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        val rawName = rawFileName ?: getFileNameFromUri(uri) ?: context.getString(R.string.proc_default_pdf_filename)
        val resolvedFileName = sanitizeFileNameWithExtension(rawName, "pdf")
        val fallbackTitle = resolvedFileName.substringBeforeLast('.')

        showProgressNotification(notificationId, fallbackTitle, context.getString(R.string.proc_progress_converting_pdf), indeterminate = true)

        try {
            val extracted = app.pdfExtractor.extractFromUri(uri, fallbackTitle)

            // Extract first page as cover specifically for PDFs
            val coverBitmap = app.pdfExtractor.renderFirstPageAsBitmap(uri)
            val coverImage = if (coverBitmap != null) {
                val opt = app.imageOptimizer.optimizeBitmap(coverBitmap, isCover = true)
                coverBitmap.recycle()
                opt
            } else null

            val finalContentHtml = extracted.contentHtml

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val cleanTitle = sanitizeFilename(extracted.title)
            val fileName = "${timestamp}_${cleanTitle}.epub"

            val docType = if (extracted.pageCount > 20) DocumentType.BOOK else DocumentType.ARTICLE
            val targetDir = File(app.libraryDir, docType.subfolder).apply { mkdirs() }
            val targetFile = ensureInsideDirectory(File(targetDir, fileName), targetDir)

            val built = app.epubBuilder.buildEpub(
                title = extracted.title,
                author = extracted.author,
                sourceUrl = resolvedFileName,
                contentHtml = finalContentHtml,
                coverImage = coverImage,
                inlineImages = emptyList(),
                outputFile = targetFile,
                wordCount = extracted.wordCount,
                readingTimeMinutes = extracted.readingTimeMinutes
            )

            if (built) {
                com.crosssend.app.util.EpubCoverLoader.saveColorCoverForDocument(context, fileName, coverImage?.colorCoverFile)
            }
            coverImage?.localFile?.delete()
            coverImage?.colorCoverFile?.delete()

            val excerpt = org.jsoup.Jsoup.parse(finalContentHtml).text().take(300).takeIf { it.isNotBlank() }

            if (built) {
                val doc = DocumentEntity(
                    title = extracted.title,
                    author = extracted.author,
                    sourceUrl = null,
                    type = docType,
                    localPath = targetFile.absolutePath,
                    fileName = fileName,
                    remotePath = "/crosssend/${docType.subfolder}/$fileName",
                    fileSizeBytes = targetFile.length(),
                    syncStatus = SyncStatus.PENDING_UPLOAD,
                    excerpt = excerpt
                )
                app.database.documentDao().insert(doc)

                if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                    showProgressNotification(notificationId, extracted.title, context.getString(R.string.proc_progress_transferring), indeterminate = true)
                    app.syncManager.performSync()
                    showSuccessNotification(notificationId, extracted.title, context.getString(R.string.proc_success_pdf_transferred))
                } else {
                    showSuccessNotification(notificationId, extracted.title, context.getString(R.string.proc_success_pdf_saved))
                }
            } else {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_eink_failed))
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "PDF processing failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_pdf, e.localizedMessage ?: ""))
        }
    }

    suspend fun processAcsmUri(
        uri: Uri,
        rawFileName: String,
        notificationId: Int = nextNotificationId()
    ) = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        val safeRawName = sanitizeFileNameWithExtension(rawFileName, "acsm")
        showProgressNotification(notificationId, safeRawName, context.getString(R.string.proc_progress_reading_acsm), indeterminate = true)

        try {
            val acsmStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(context.getString(R.string.proc_error_acsm_read))
            val acsmBytes = acsmStream.use { readBoundedBytes(it, MAX_ACSM_BYTES) }
                ?: throw IllegalArgumentException(context.getString(R.string.proc_error_acsm_too_large))
            val acsmContent = String(acsmBytes, Charsets.UTF_8)

            // Extract metadata from ACSM XML
            val titleRegex = "<(?:[a-zA-Z0-9]+:)?title[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?title>".toRegex(RegexOption.IGNORE_CASE)
            val creatorRegex = "<(?:[a-zA-Z0-9]+:)?creator[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?creator>".toRegex(RegexOption.IGNORE_CASE)
            val srcRegex = "<(?:[a-zA-Z0-9]+:)?src[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?src>".toRegex(RegexOption.IGNORE_CASE)
            val formatRegex = "<(?:[a-zA-Z0-9]+:)?format[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?format>".toRegex(RegexOption.IGNORE_CASE)

            val extractedTitle = titleRegex.find(acsmContent)?.groupValues?.get(1)?.trim()
            val extractedCreator = creatorRegex.find(acsmContent)?.groupValues?.get(1)?.trim()
            val srcUrl = srcRegex.find(acsmContent)?.groupValues?.get(1)?.trim()?.let {
                org.jsoup.parser.Parser.unescapeEntities(it, true)
            }
            val format = formatRegex.find(acsmContent)?.groupValues?.get(1)?.trim()?.lowercase() ?: ""

            val cleanTitle = if (!extractedTitle.isNullOrBlank()) {
                sanitizeFilename(extractedTitle)
            } else {
                sanitizeFilename(safeRawName.removeSuffix(".acsm"))
            }

            val author = extractedCreator?.takeIf { it.isNotBlank() }
            val booksDir = File(app.libraryDir, "books").apply { mkdirs() }

            // Attempt direct download if <src> URL is available and accessible
            var downloadedFile: File? = null
            if (!srcUrl.isNullOrBlank() && srcUrl.startsWith("https://", ignoreCase = true)) {
                try {
                    require(EInkImageOptimizer.isSafeExternalImageUrl(srcUrl)) { "Invalid or internal ACSM download URL blocked: $srcUrl" }
                    showProgressNotification(notificationId, cleanTitle, context.getString(R.string.proc_progress_downloading_book), indeterminate = true)
                    val client = try {
                        app.wifiManager.getInternetOkHttpClientBuilder()
                            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                            .addInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                            .addNetworkInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                            .build()
                    } catch (_: Exception) {
                        okhttp3.OkHttpClient.Builder()
                            .dns(EInkImageOptimizer.ssrfSafeDns)
                            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                            .addInterceptor(okhttp3.Interceptor { chain ->
                                val req = chain.request()
                                if (!req.isHttps) {
                                    throw java.io.IOException("Cleartext HTTP blocked for ACSM download: ${req.url}")
                                }
                                chain.proceed(req)
                            })
                            .addInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                            .addNetworkInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                            .build()
                    }

                    val req = okhttp3.Request.Builder().url(srcUrl).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyBytes = resp.body?.let { EInkImageOptimizer.readBoundedBytes(it, 50L * 1024 * 1024) }
                            if (bodyBytes != null && bodyBytes.size > 100) {
                                // Check if it is a valid EPUB (ZIP header 'PK') or PDF ('%PDF')
                                val isZip = bodyBytes.size >= 4 && bodyBytes[0] == 0x50.toByte() && bodyBytes[1] == 0x4B.toByte()
                                val isPdf = bodyBytes.size >= 4 && bodyBytes[0] == 0x25.toByte() && bodyBytes[1] == 0x50.toByte() && bodyBytes[2] == 0x44.toByte()

                                if (isZip || isPdf || format.contains("epub") || format.contains("pdf")) {
                                    val ext = if (isPdf || format.contains("pdf")) "pdf" else "epub"
                                    val safeDownloadFileName = sanitizeFileNameWithExtension("${cleanTitle}.$ext", ext)
                                    val targetFile = ensureInsideDirectory(File(booksDir, safeDownloadFileName), booksDir)
                                    targetFile.writeBytes(bodyBytes)
                                    downloadedFile = targetFile
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Direct download from ACSM <src> failed (expected if DRM-protected): ${e.message}")
                }
            }

            val finalFile: File
            val finalFileName: String
            val downloaded = downloadedFile
            val isEpub = downloaded != null
            val remotePath: String

            if (downloaded != null) {
                finalFile = downloaded
                finalFileName = downloaded.name
                remotePath = "/crosssend/books/$finalFileName"
            } else {
                // Save the .acsm file. It must land in "/" on the reader for Protected Content
                finalFileName = sanitizeFileNameWithExtension("${cleanTitle}.acsm", "acsm")
                finalFile = ensureInsideDirectory(File(booksDir, finalFileName), booksDir)
                finalFile.writeBytes(acsmBytes)
                remotePath = "/$finalFileName"
            }

            val doc = DocumentEntity(
                title = extractedTitle ?: cleanTitle,
                author = author,
                sourceUrl = srcUrl,
                type = DocumentType.BOOK,
                localPath = finalFile.absolutePath,
                fileName = finalFileName,
                remotePath = remotePath,
                fileSizeBytes = finalFile.length(),
                syncStatus = SyncStatus.PENDING_UPLOAD
            )
            app.database.documentDao().insert(doc)

            if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                app.syncManager.performSync()
                val targetInfo = if (isEpub) "/crosssend/books/" else context.getString(R.string.proc_acsm_root_dir)
                showSuccessNotification(notificationId, doc.title, context.getString(R.string.proc_success_acsm_transferred, targetInfo))
            } else {
                val targetInfo = if (isEpub) "in /crosssend/books/" else "in /"
                showSuccessNotification(notificationId, doc.title, context.getString(R.string.proc_success_acsm_provided, targetInfo))
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processAcsmUri failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_acsm, e.localizedMessage ?: ""))
        }
    }

    suspend fun processFileUri(uri: Uri, mimeType: String, notificationId: Int = nextNotificationId()) = withContext(Dispatchers.IO) {
        if (!isSafeSharedUri(uri)) {
            throw SecurityException(context.getString(R.string.proc_error_internal_access_denied))
        }
        val app = CrossSendApp.instance
        val rawFileName = getFileNameFromUri(uri) ?: "${context.getString(R.string.proc_default_file_prefix)}_${System.currentTimeMillis()}"
        val fileName = sanitizeFileNameWithExtension(rawFileName)

        val isPdf = fileName.endsWith(".pdf", true) || mimeType.contains("pdf")
        if (isPdf) {
            processPdfUri(uri, fileName, notificationId)
            return@withContext
        }

        val isAcsm = fileName.endsWith(".acsm", true) ||
                mimeType.contains("acsm", ignoreCase = true) ||
                mimeType.contains("adobe.adept", ignoreCase = true)
        if (isAcsm) {
            processAcsmUri(uri, fileName, notificationId)
            return@withContext
        }

        val isHtml = fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) || mimeType.contains("html", ignoreCase = true)
        if (isHtml) {
            val htmlStream = context.contentResolver.openInputStream(uri)
            if (htmlStream != null) {
                val htmlBytes = htmlStream.use { readBoundedBytes(it, MAX_HTML_BYTES) }
                if (htmlBytes == null) {
                    showErrorNotification(notificationId, context.getString(R.string.proc_error_html_too_large))
                    return@withContext
                }
                val htmlContent = String(htmlBytes, Charsets.UTF_8)
                if (htmlContent.isNotBlank()) {
                    val titleFromFileName = fileName.substringBeforeLast('.')
                    processHtmlContent(htmlContent, baseUrl = null, subject = titleFromFileName, notificationId = notificationId)
                    return@withContext
                }
            }
        }

        val isEpub = fileName.endsWith(".epub", true) || mimeType.contains("epub", ignoreCase = true)
        val isText = fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) || mimeType.startsWith("text/", ignoreCase = true)
        val isImage = fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) ||
                      fileName.endsWith(".jpeg", true) || fileName.endsWith(".webp", true) ||
                      fileName.endsWith(".bmp", true) || mimeType.startsWith("image/", ignoreCase = true)

        if (isImage) {
            // Drop browser screenshot thumbnail hashes completely
            if (fileName.matches(Regex("""^\d{15,}\.(png|jpg|webp)$""")) || uri.toString().contains("screenshot", ignoreCase = true)) {
                Log.w(tag, "Ignoring browser screenshot thumbnail: $fileName")
                return@withContext
            }
            processSleepScreenUri(uri, notificationId)
            return@withContext
        }

        val docType = when {
            isEpub -> DocumentType.BOOK
            isText -> DocumentType.TEXT
            else -> {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_unsupported_format, fileName))
                return@withContext
            }
        }

        showProgressNotification(notificationId, fileName, context.getString(R.string.proc_progress_copying_file), indeterminate = true)

        try {
            val targetDir = File(app.libraryDir, docType.subfolder).apply { mkdirs() }
            val targetFile = ensureInsideDirectory(File(targetDir, fileName), targetDir)

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(context.getString(R.string.proc_error_file_read))
            val copiedBytes = try {
                inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        copyBounded(input, output, MAX_FILE_COPY_BYTES)
                    }
                }
            } catch (e: Exception) {
                targetFile.delete()
                throw e
            }
            if (copiedBytes < 0L) {
                targetFile.delete()
                throw IllegalArgumentException(context.getString(R.string.proc_error_file_too_large))
            }

            val remotePath = "/crosssend/${docType.subfolder}/$fileName"

            val doc = DocumentEntity(
                title = fileName.substringBeforeLast('.'),
                author = null,
                sourceUrl = null,
                type = docType,
                localPath = targetFile.absolutePath,
                fileName = fileName,
                remotePath = remotePath,
                fileSizeBytes = targetFile.length(),
                syncStatus = SyncStatus.PENDING_UPLOAD
            )
            app.database.documentDao().insert(doc)

            if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                app.syncManager.performSync()
                showSuccessNotification(notificationId, fileName, context.getString(R.string.proc_success_transferred))
            } else {
                showSuccessNotification(notificationId, fileName, context.getString(R.string.proc_success_saved_in, remotePath))
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processFileUri failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_copying_file, e.localizedMessage ?: ""))
        }
    }

    /**
     * Converts any picked image URI into an uncompressed 24-bit BMP at the target device's
     * hardware resolution (480x800 for X4, 528x792 for X3), saves as DocumentType.SLEEP,
     * and triggers device sync.
     */
    suspend fun processSleepScreenUri(uri: Uri, notificationId: Int = nextNotificationId()): DocumentEntity? = withContext(Dispatchers.IO) {
        if (!isSafeSharedUri(uri)) {
            Log.w(tag, "processSleepScreenUri: Unsafe URI rejected: $uri")
            showErrorNotification(notificationId, context.getString(R.string.proc_error_internal_access_denied))
            return@withContext null
        }
        val app = CrossSendApp.instance
        try {
            showProgressNotification(notificationId, "Sleepscreen", context.getString(R.string.proc_progress_optimizing_sleepscreen), indeterminate = true)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(context.getString(R.string.proc_error_image_read))
            val bytes = inputStream.use { readBoundedBytes(it, MAX_SLEEP_IMAGE_BYTES) }
                ?: throw IllegalArgumentException(context.getString(R.string.proc_error_image_too_large))

            val bmpBytes = imageOptimizer.optimizeSleepImage(bytes)
                ?: throw IllegalStateException(context.getString(R.string.proc_error_bmp_convert))

            val rawFileName = getFileNameFromUri(uri) ?: "sleep_${System.currentTimeMillis()}"
            val baseName = sanitizeFilename(rawFileName.substringBeforeLast('.'))
            val finalFileName = "${baseName}.bmp"

            val sleepDir = File(app.libraryDir, DocumentType.SLEEP.subfolder).apply { mkdirs() }
            val targetFile = ensureInsideDirectory(File(sleepDir, finalFileName), sleepDir)
            targetFile.writeBytes(bmpBytes)

            val remotePath = "/sleep/$finalFileName"
            val doc = DocumentEntity(
                title = baseName,
                author = null,
                sourceUrl = null,
                type = DocumentType.SLEEP,
                localPath = targetFile.absolutePath,
                fileName = finalFileName,
                remotePath = remotePath,
                fileSizeBytes = targetFile.length(),
                createdAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING_UPLOAD
            )
            app.database.documentDao().insert(doc)

            if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                showProgressNotification(notificationId, baseName, context.getString(R.string.proc_progress_transferring), indeterminate = true)
                app.syncManager.performSync()
                showSuccessNotification(notificationId, baseName, context.getString(R.string.proc_success_sleepscreen_transferred))
            } else {
                showSuccessNotification(notificationId, baseName, context.getString(R.string.proc_success_sleepscreen_saved))
            }
            doc
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processSleepScreenUri failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_sleepscreen, e.localizedMessage ?: ""))
            null
        }
    }

    suspend fun processRssItem(item: RssItem, feedTitle: String, notificationId: Int = nextNotificationId()) = withContext(Dispatchers.IO) {
        val app = CrossSendApp.instance
        if (item.link.isNotBlank() &&
            EInkImageOptimizer.isSafeExternalImageUrl(item.link) &&
            resolveRenderModeForUrl(item.link) == RenderMode.ORIGINAL_LAYOUT
        ) {
            processWebUrlOriginalLayout(
                url = item.link,
                fallbackTitle = item.title,
                author = item.author ?: feedTitle,
                publishedDate = item.publishedDate,
                notificationId = notificationId
            )
            return@withContext
        }
        showProgressNotification(notificationId, item.title, context.getString(R.string.proc_progress_optimizing_rss), indeterminate = true)

        try {
            val finalContentHtml = item.contentHtml

            val effectiveDate = item.publishedDate ?: Date()
            val datePrefix = DateUtils.formatForFilename(effectiveDate)
            val cleanTitle = sanitizeFilename(item.title)
            val fileName = "${datePrefix}_${cleanTitle}.epub"

            val articlesDir = File(app.libraryDir, "articles").apply { mkdirs() }
            val targetEpubFile = ensureInsideDirectory(File(articlesDir, fileName), articlesDir)

            showProgressNotification(notificationId, item.title, context.getString(R.string.proc_progress_optimizing_images), indeterminate = true)
            val coverImage = item.imageUrls.firstOrNull()?.let {
                app.imageOptimizer.optimizeImage(it, 0, isCover = true)
            }

            val remainingImages = if (coverImage != null) item.imageUrls.drop(1) else item.imageUrls
            val inlineImages = remainingImages.take(10).mapIndexedNotNull { i, imgUrl ->
                app.imageOptimizer.optimizeImage(imgUrl, i + 1, isCover = false)
            }

            showProgressNotification(notificationId, item.title, context.getString(R.string.proc_progress_creating_epub), indeterminate = true)
            val built = app.epubBuilder.buildEpub(
                title = item.title,
                subtitle = item.description,
                author = item.author ?: feedTitle,
                sourceUrl = item.link,
                contentHtml = finalContentHtml,
                coverImage = coverImage,
                inlineImages = inlineImages,
                outputFile = targetEpubFile,
                wordCount = item.wordCount,
                readingTimeMinutes = item.readingTimeMinutes,
                publishedDate = item.publishedDate
            )

            if (built) {
                com.crosssend.app.util.EpubCoverLoader.saveColorCoverForDocument(context, fileName, coverImage?.colorCoverFile)
            }
            coverImage?.localFile?.delete()
            coverImage?.colorCoverFile?.delete()
            inlineImages.forEach { it.localFile.delete() }

            val excerpt = item.description?.takeIf { it.isNotBlank() }?.let { org.jsoup.Jsoup.parse(it).text() }?.take(300)
                ?: org.jsoup.Jsoup.parse(finalContentHtml).text().take(300).takeIf { it.isNotBlank() }

            if (built) {
                upsertArticleDocument(
                    title = item.title,
                    author = item.author ?: feedTitle,
                    sourceUrl = item.link,
                    targetFile = targetEpubFile,
                    fileName = fileName,
                    excerpt = excerpt,
                    renderMode = RenderMode.READER,
                    publishedTimestamp = effectiveDate.time
                )

                // Record debug dump for RSS item
                val mockArticle = ExtractedArticle(
                    title = item.title,
                    subtitle = item.description,
                    author = item.author ?: feedTitle,
                    siteName = feedTitle,
                    leadImageUrl = coverImage?.let { item.imageUrls.firstOrNull() },
                    contentHtml = finalContentHtml,
                    imageUrls = remainingImages,
                    sourceUrl = item.link,
                    wordCount = item.wordCount,
                    readingTimeMinutes = item.readingTimeMinutes,
                    publishedDate = item.publishedDate
                )
                com.crosssend.app.util.DebugDumpManager.dumpItem(
                    context = context,
                    sourceType = "RSS_FEED",
                    sourceUrl = item.link,
                    inputSubject = feedTitle,
                    rawInput = item.contentHtml.ifBlank { item.description ?: "" },
                    article = mockArticle,
                    epubFile = targetEpubFile
                )

                if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                    app.syncManager.performSync()
                    showSuccessNotification(notificationId, item.title, context.getString(R.string.proc_success_transferred))
                } else {
                    showSuccessNotification(notificationId, item.title, context.getString(R.string.proc_success_saved_for_sync))
                }
            } else {
                showErrorNotification(notificationId, context.getString(R.string.proc_error_epub_failed))
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                dismissNotification(notificationId)
                throw e
            }
            Log.e(tag, "processRssItem failed", e)
            showErrorNotification(notificationId, context.getString(R.string.proc_error_prefix, e.localizedMessage ?: ""))
        }
    }

    fun showProgressNotification(
        notificationId: Int,
        title: String,
        message: String,
        max: Int = 0,
        progress: Int = 0,
        indeterminate: Boolean = true
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setProgress(max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVibrate(null)
            .setSound(null)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission missing", e)
        }
    }

    fun dismissNotification(notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(tag, "Failed to dismiss notification", e)
        }
    }

    fun showSuccessNotification(notificationId: Int, title: String, subtitle: String) {
        // Keine Bestätigungsmeldung erwünscht: Fortschrittsbenachrichtigung verschwindet nach Fertigstellung
        dismissNotification(notificationId)
    }

    fun showErrorNotification(notificationId: Int, error: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.proc_notification_alert_title))
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVibrate(null)
            .setSound(null)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission missing", e)
        }
    }

    fun showPaywallLoginNotification(notificationId: Int, url: String, articleTitle: String?) {
        val titleText = articleTitle ?: context.getString(R.string.proc_default_web_article_title)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WEBVIEW_URL, url)
            putExtra(MainActivity.EXTRA_WEBVIEW_TITLE, titleText)
            putExtra("extra_internal_token", com.crosssend.app.CrossSendApp.INTERNAL_NOTIFICATION_TOKEN)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.proc_login_required_title, titleText))
            .setContentText(context.getString(R.string.proc_login_required_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.proc_login_required_big_text)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVibrate(null)
            .setSound(null)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            Log.w(tag, "Notification permission missing", e)
        }
    }

    private fun createNotificationChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            manager.deleteNotificationChannel("crosssend_share_channel")
            manager.deleteNotificationChannel("crosssend_alerts_channel")
        } catch (_: Exception) {}

        val progressChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.proc_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.proc_channel_progress_desc)
            enableVibration(false)
            vibrationPattern = null
            setSound(null, null)
        }
        manager.createNotificationChannel(progressChannel)

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS_ID,
            context.getString(R.string.proc_channel_alerts_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.proc_channel_alerts_desc)
            enableVibration(false)
            vibrationPattern = null
            setSound(null, null)
        }
        manager.createNotificationChannel(alertChannel)
    }

    fun extractTimestampFromUrl(url: String?): Date? {
        if (url.isNullOrBlank()) return null
        val match = "/([1-2][0-9]{9})(/|$)".toRegex().find(url) ?: return null
        val epochSeconds = match.groupValues[1].toLongOrNull() ?: return null
        if (epochSeconds in 1500000000L..2000000000L) { // 2017 to 2033
            return Date(epochSeconds * 1000L)
        }
        return null
    }

    private fun buildFallbackParagraphHtml(bodyLines: List<String>, title: String): String {
        val paragraphs = bodyLines
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (line.startsWith("&gt;") || line.startsWith(">")) {
                    "<blockquote>$escaped</blockquote>"
                } else {
                    "<p>$escaped</p>"
                }
            }
        return paragraphs.ifBlank { "<p>${title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</p>" }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    fun isHtmlContent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true) ||
            trimmed.startsWith("<?xml", ignoreCase = true)) {
            return true
        }
        val htmlTagPattern = "<(html|head|body|article|main|div|section|p|h1|h2|script|style)[^>]*>".toRegex(RegexOption.IGNORE_CASE)
        val matches = htmlTagPattern.findAll(trimmed).take(5).toList()
        return matches.size >= 3
    }

    private fun inferBaseUrlFromHtml(html: String): String {
        val canonicalMatch = "<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE).find(html)
            ?: "<link[^>]+href=[\"']([^\"']+)[\"'][^>]+rel=[\"']canonical[\"']".toRegex(RegexOption.IGNORE_CASE).find(html)
        if (canonicalMatch != null) return canonicalMatch.groupValues[1]

        val ogUrlMatch = "<meta[^>]+property=[\"']og:url[\"'][^>]+content=[\"']([^\"']+)[\"']".toRegex(RegexOption.IGNORE_CASE).find(html)
            ?: "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:url[\"']".toRegex(RegexOption.IGNORE_CASE).find(html)
        if (ogUrlMatch != null) return ogUrlMatch.groupValues[1]

        // Find common absolute links
        val urlRegex = "https?://([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6})".toRegex()
        val hosts = urlRegex.findAll(html.take(20000)).map { it.groupValues[1] }
            .filter { !it.contains("google") && !it.contains("schema.org") && !it.contains("w3.org") }
            .toList()
        val topHost = hosts.groupBy { it }.maxByOrNull { it.value.size }?.key
        if (topHost != null) return "https://$topHost"

        return "https://crosssend.local"
    }

    private fun cleanFallbackTitle(rawTitle: String): String {
        return CrossSendApp.instance.articleExtractor.cleanTitle(rawTitle).ifBlank { rawTitle }
    }

    fun sanitizeFilename(name: String): String {
        val fallback = if (Locale.getDefault().language == "de") "Dokument" else "Document"
        return name.replace(Regex("[^a-zA-Z0-9äöüÄÖÜß_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { fallback }
            .take(50)
    }

    fun sanitizeFileNameWithExtension(rawName: String, fallbackExt: String = ""): String {
        val cleanName = rawName.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = cleanName.lastIndexOf('.')
        val (baseName, ext) = if (dotIndex >= 0) {
            cleanName.substring(0, dotIndex) to cleanName.substring(dotIndex + 1)
        } else {
            cleanName to fallbackExt
        }
        val safeBase = sanitizeFilename(baseName)
        val safeExt = ext.replace(Regex("[^a-zA-Z0-9]"), "").take(10)
        return if (safeExt.isNotBlank()) "$safeBase.$safeExt" else safeBase
    }

    fun ensureInsideDirectory(file: File, dir: File): File {
        val dirCanonical = dir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (!fileCanonical.startsWith(dirCanonical + File.separator)) {
            throw SecurityException("Invalid file path")
        }
        return file
    }
}
