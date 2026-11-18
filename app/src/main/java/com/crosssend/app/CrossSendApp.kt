package com.crosssend.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import java.lang.ref.WeakReference
import com.crosssend.app.converter.ArticleExtractor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.converter.EpubBuilder
import com.crosssend.app.converter.PdfExtractor
import com.crosssend.app.data.local.AppDatabase
import com.crosssend.app.network.CrossPointWifiManager
import com.crosssend.app.network.WebDavClient
import com.crosssend.app.sync.SyncManager
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncStatus
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class WebArticleLoginRequest(
    val url: String,
    val title: String? = null,
    val rerenderOriginalLayoutDocId: Long? = null
)

class CrossSendApp : Application() {

    val pendingWebViewRequest = MutableStateFlow<WebArticleLoginRequest?>(null)
    val webArticleLoginRequest: MutableStateFlow<WebArticleLoginRequest?>
        get() = pendingWebViewRequest
    val rerenderingDocId = MutableStateFlow<Long?>(null)

    lateinit var database: AppDatabase
        private set

    lateinit var webDavClient: WebDavClient
        private set

    lateinit var wifiManager: CrossPointWifiManager
        private set

    lateinit var syncManager: SyncManager
        private set

    lateinit var articleExtractor: ArticleExtractor
        private set

    lateinit var imageOptimizer: EInkImageOptimizer
        private set

    lateinit var epubBuilder: EpubBuilder
        private set

    lateinit var pdfExtractor: PdfExtractor
        private set

    lateinit var rssFeedParser: com.crosssend.app.network.RssFeedParser
        private set

    lateinit var deviceClient: com.crosssend.app.network.CrossPointDeviceClient
        private set

    lateinit var documentProcessor: com.crosssend.app.converter.DocumentProcessor
        private set

    val applicationScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    val libraryDir: File
        get() = File(filesDir, "library").apply { mkdirs() }

    @Volatile
    var currentActivityRef: WeakReference<Activity>? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            WebView.enableSlowWholeDocumentDraw()
        } catch (_: Exception) {}

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun trackIfInteractive(activity: Activity) {
                if (activity !is com.crosssend.app.share.ShareHandlerActivity && !activity.isFinishing && !activity.isDestroyed) {
                    currentActivityRef = WeakReference(activity)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                trackIfInteractive(activity)
            }
            override fun onActivityStarted(activity: Activity) {
                trackIfInteractive(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                trackIfInteractive(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                }
            }
        })

        PDFBoxResourceLoader.init(applicationContext)

        database = AppDatabase.getDatabase(this)
        wifiManager = CrossPointWifiManager(this)
        webDavClient = WebDavClient(this, wifiManager = wifiManager)
        syncManager = SyncManager(this, database.documentDao(), webDavClient, wifiManager) { entry ->
            val url = entry.sourceUrl ?: return@SyncManager false
            when (entry.renderMode) {
                com.crosssend.app.data.model.RenderMode.ORIGINAL_LAYOUT -> {
                    documentProcessor.processWebUrlOriginalLayout(
                        url = url,
                        fallbackTitle = entry.title,
                        author = entry.author,
                        triggerSync = false
                    )
                }
                com.crosssend.app.data.model.RenderMode.READER -> {
                    documentProcessor.processWebUrl(
                        url = url,
                        fallbackTitle = entry.title,
                        triggerSync = false
                    )
                }
            }
        }
        articleExtractor = ArticleExtractor()
        imageOptimizer = EInkImageOptimizer(cacheDir)
        epubBuilder = EpubBuilder()
        pdfExtractor = PdfExtractor(this)
        rssFeedParser = com.crosssend.app.network.RssFeedParser()
        deviceClient = com.crosssend.app.network.CrossPointDeviceClient(wifiManager = wifiManager)
        documentProcessor = com.crosssend.app.converter.DocumentProcessor(this)

        com.crosssend.app.sync.RssSyncWorker.schedulePeriodicSync(this)

        applicationScope.launch {
            val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000L
            database.documentDao().deleteArchivedOlderThan(ninetyDaysAgo)

            val twoHoursAgo = System.currentTimeMillis() - 2L * 60 * 60 * 1000L
            try {
                val feeds = database.feedDao().getAllFeedsList()
                if (feeds.any { it.lastFetchedAt < twoHoursAgo }) {
                    com.crosssend.app.sync.RssSyncWorker.syncAllFeeds(this@CrossSendApp)
                }
            } catch (e: Exception) {
                android.util.Log.w("CrossSendApp", "Initial RSS sync notice: ${e.message}")
            }

            reconcileLocalFilesWithDatabase()
            val detected = wifiManager.autoDetectCrossPoint(preferredUrl = webDavClient.getBaseUrl())
            if (detected != null) {
                webDavClient.updateBaseUrl(detected)
            }
        }
    }

    suspend fun reconcileLocalFilesWithDatabase() {
        // 1. Purge accidental browser screenshot thumbnails from local storage
        val invalidFolders = listOf(File(libraryDir, "texts"), File(libraryDir, "articles"))
        for (folder in invalidFolders) {
            if (!folder.exists()) continue
            val junkFiles = folder.listFiles { file ->
                val name = file.name.lowercase()
                name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".webp") || file.name.matches(Regex("""^\d{15,}.*"""))
            } ?: emptyArray()
            for (junk in junkFiles) {
                junk.delete()
            }
        }

        // 2. Purge accidental bogus DB records created from browser screenshot thumbnails
        val bogusDocs = database.documentDao().getAllDocuments().filter { doc ->
            doc.type != DocumentType.SLEEP && (
                doc.fileName.matches(Regex("""^\d{15,}\.(png|jpg|webp)$""")) ||
                doc.title.matches(Regex("""^\d{15,}$""")) ||
                (doc.type == DocumentType.TEXT && (doc.fileName.endsWith(".png", true) || doc.fileName.endsWith(".jpg", true)))
            )
        }
        for (bogus in bogusDocs) {
            File(bogus.localPath).delete()
            database.documentDao().delete(bogus)
            if (webDavClient.isReachable()) {
                webDavClient.deleteFile(bogus.remotePath)
            }
        }

        val types = listOf(
            DocumentType.ARTICLE to File(libraryDir, "articles"),
            DocumentType.BOOK to File(libraryDir, "books"),
            DocumentType.TEXT to File(libraryDir, "texts")
        )

        for ((type, folder) in types) {
            if (!folder.exists()) continue
            val files = folder.listFiles() ?: continue
            for (file in files) {
                if (file.isDirectory) continue
                val fileName = file.name
                if (fileName.startsWith(".")) continue

                // Only import valid file types per folder
                val isValid = when (type) {
                    DocumentType.ARTICLE -> fileName.endsWith(".epub", true) || fileName.endsWith(".xtch", true) || fileName.endsWith(".xtc", true)
                    DocumentType.BOOK -> fileName.endsWith(".epub", true) || fileName.endsWith(".pdf", true)
                    DocumentType.TEXT -> fileName.endsWith(".txt", true) || fileName.endsWith(".md", true)
                    DocumentType.SLEEP -> fileName.endsWith(".png", true) || fileName.endsWith(".jpg", true) || fileName.endsWith(".bmp", true)
                }
                if (!isValid) continue

                val remotePath = "/crosssend/${type.subfolder}/$fileName"
                val existing = database.documentDao().findByRemotePath(remotePath)
                if (existing == null) {
                    val baseName = fileName.substringBeforeLast('.')
                    val title = if (baseName.matches(Regex("^\\d{8}_\\d{4}_.*"))) {
                        baseName.substring(14).replace('_', ' ')
                    } else {
                        baseName.replace('_', ' ')
                    }
                    val inferredMode = if (fileName.endsWith(".xtch", true) || fileName.endsWith(".xtc", true)) {
                        com.crosssend.app.data.model.RenderMode.ORIGINAL_LAYOUT
                    } else {
                        com.crosssend.app.data.model.RenderMode.READER
                    }

                    val doc = DocumentEntity(
                        title = title,
                        author = null,
                        sourceUrl = null,
                        type = type,
                        localPath = file.absolutePath,
                        fileName = fileName,
                        remotePath = remotePath,
                        fileSizeBytes = file.length(),
                        createdAt = com.crosssend.app.util.DateUtils.parseFilenameTimestamp(fileName)?.time ?: file.lastModified(),
                        syncStatus = SyncStatus.SYNCED,
                        renderMode = inferredMode
                    )
                    database.documentDao().insert(doc)
                }
            }
        }
    }

    companion object {
        lateinit var instance: CrossSendApp
            private set
        val INTERNAL_NOTIFICATION_TOKEN: String = java.util.UUID.randomUUID().toString()
    }
}
