package com.crosssend.app.sync

import android.content.Context
import android.util.Log
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentDao
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncResult
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.WebDavClient
import com.crosssend.app.util.DocumentPreviewReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

class SyncManager(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val webDavClient: WebDavClient,
    private val onFetchRemoteArticle: (suspend (SyncManifestEntry) -> Boolean)? = null
) {
    private val tag = "SyncManager"
    private val syncMutex = Mutex()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncStatusText = MutableStateFlow("")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(0L)
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val _uploadProgressByDocId = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val uploadProgressByDocId: StateFlow<Map<Long, Float>> = _uploadProgressByDocId.asStateFlow()

    /**
     * Executes a full 2-way sync with the CrossPoint Reader.
     */
    suspend fun performSync(): SyncResult = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) {
            return@withContext SyncResult.Error(context.getString(R.string.sync_error_already_running))
        }

        _isSyncing.value = true
        _syncStatusText.value = context.getString(R.string.sync_status_checking_connection)

        try {
            if (!webDavClient.isReachable()) {
                _syncStatusText.value = context.getString(R.string.sync_status_unreachable)
                return@withContext SyncResult.Error(context.getString(R.string.sync_error_webdav_unreachable))
            }

            // 1. Ensure dedicated folder structure exists: /crosssend/{articles, books, texts} and /sleep
            _syncStatusText.value = context.getString(R.string.sync_status_creating_dirs)
            webDavClient.ensureDirectoryExists("/crosssend")
            webDavClient.ensureDirectoryExists("/crosssend/articles")
            webDavClient.ensureDirectoryExists("/crosssend/books")
            webDavClient.ensureDirectoryExists("/crosssend/texts")
            webDavClient.ensureDirectoryExists("/sleep")

            // 2. Handle Pending Deletions (Deletions initiated in the App)
            _syncStatusText.value = context.getString(R.string.sync_status_deleting_files)
            val pendingDeletes = documentDao.getPendingDeletes()
            var deletedCount = 0
            for (doc in pendingDeletes) {
                Log.d(tag, "Deleting remote file: ${doc.remotePath}")
                val remoteDeleted = webDavClient.deleteFile(doc.remotePath)
                val remoteDir = doc.remotePath.substringBeforeLast('/', "")
                val readSubPath = if (remoteDir == "/" || remoteDir.isEmpty()) "/read/${doc.fileName}" else "${remoteDir.trimEnd('/')}/read/${doc.fileName}"
                try {
                    webDavClient.deleteFile(readSubPath)
                } catch (_: Exception) {}

                if (remoteDeleted) {
                    val localFile = File(doc.localPath)
                    val excerpt = if (doc.type != DocumentType.SLEEP) {
                        doc.excerpt ?: try {
                            DocumentPreviewReader.readPreviewText(doc).take(300)
                        } catch (_: Exception) {
                            null
                        }
                    } else null

                    // Remove local cached file
                    if (localFile.exists()) {
                        localFile.delete()
                    }

                    // Archive non-sleep documents in 90-day archive or delete sleep screens
                    if (doc.type != DocumentType.SLEEP) {
                        documentDao.markArchived(doc.id, System.currentTimeMillis(), excerpt)
                    } else {
                        documentDao.delete(doc)
                    }
                    deletedCount++
                }
            }

            // 3. Handle Pending Uploads (New articles, books, texts)
            _syncStatusText.value = context.getString(R.string.sync_status_uploading_files)

            // Ensure any ACSM files are routed to "/" (root) for Protected Content plugin
            val allAcsm = documentDao.getAllDocuments().filter { it.fileName.endsWith(".acsm", true) && it.remotePath != "/${it.fileName}" }
            for (acsm in allAcsm) {
                documentDao.update(acsm.copy(remotePath = "/${acsm.fileName}", syncStatus = SyncStatus.PENDING_UPLOAD))
            }

            val pendingUploads = documentDao.getPendingUploads()
            var uploadedCount = 0
            for (doc in pendingUploads) {
                val localFile = File(doc.localPath)
                if (!localFile.exists()) {
                    Log.w(tag, "Local file not found for upload: ${doc.localPath}. Removing orphaned document record.")
                    documentDao.delete(doc)
                    continue
                }

                val targetPath = if (doc.fileName.endsWith(".acsm", true)) "/${doc.fileName}" else doc.remotePath
                _syncStatusText.value = context.getString(R.string.sync_status_sending_file, doc.fileName)
                _uploadProgressByDocId.value = _uploadProgressByDocId.value + (doc.id to 0f)
                var lastReportedProgress = 0f
                val success = webDavClient.uploadFile(localFile, targetPath) { progress ->
                    val clamped = progress.coerceIn(0f, 1f)
                    if (clamped - lastReportedProgress >= 0.02f || clamped >= 0.99f) {
                        lastReportedProgress = clamped
                        _uploadProgressByDocId.value = _uploadProgressByDocId.value + (doc.id to clamped)
                    }
                }
                _uploadProgressByDocId.value = _uploadProgressByDocId.value - doc.id
                if (success) {
                    val obsoleteRemotePath = doc.pendingDeleteRemotePath
                    if (!obsoleteRemotePath.isNullOrBlank() && obsoleteRemotePath != targetPath) {
                        try {
                            webDavClient.deleteFile(obsoleteRemotePath)
                        } catch (_: Exception) {}
                    }
                    if (obsoleteRemotePath != null) {
                        documentDao.clearPendingDeleteRemotePath(doc.id)
                    }
                    if (targetPath != doc.remotePath) {
                        documentDao.update(doc.copy(remotePath = targetPath, pendingDeleteRemotePath = null))
                    }
                    documentDao.markSynced(doc.id)
                    uploadedCount++
                } else {
                    documentDao.updateSyncStatus(doc.id, SyncStatus.FAILED, context.getString(R.string.sync_error_upload_failed))
                }
            }

            // 4. Reconcile Read Folder (Detect read articles moved to /read on CrossPoint)
            _syncStatusText.value = context.getString(R.string.sync_status_checking_read)
            val readCleanedCount = reconcileReadArticles()
            deletedCount += readCleanedCount

            // 5. Reconcile Hidden Sync Manifest (/crosssend/.crosssend-sync.json)
            _syncStatusText.value = context.getString(R.string.sync_status_syncing_manifest)
            val (manifestUploaded, manifestDeleted) = reconcileSyncManifest()
            uploadedCount += manifestUploaded
            deletedCount += manifestDeleted

            _lastSyncTimestamp.value = System.currentTimeMillis()
            _syncStatusText.value = context.getString(R.string.sync_status_completed)
            SyncResult.Success(uploadedCount, deletedCount)

        } catch (e: Exception) {
            Log.e(tag, "Sync failed with exception", e)
            val msg = e.message ?: context.getString(R.string.sync_error_unknown)
            _syncStatusText.value = context.getString(R.string.proc_error_prefix, msg)
            SyncResult.Error(msg)
        } finally {
            _uploadProgressByDocId.value = emptyMap()
            _isSyncing.value = false
            syncMutex.unlock()
        }
    }
    /**
     * Checks read folders on CrossPoint (e.g. /read, /Read, /crosssend/read, /crosssend/articles/read).
     * If an article created by CrossSend is found in a read folder, it is deleted from both
     * the CrossPoint reader SD card and the local app library.
     */
    private suspend fun reconcileReadArticles(): Int {
        val readFolders = listOf(
            "/read",
            "/Read",
            "/crosssend/read",
            "/crosssend/articles/read"
        )
        var cleanedCount = 0

        for (folder in readFolders) {
            val remoteItems = webDavClient.listDirectory(folder)
            if (remoteItems.isEmpty()) continue

            for (item in remoteItems) {
                if (item.isDirectory) continue

                // Check if this file belongs to an article from CrossSend
                val doc = documentDao.findByFileName(item.name)
                    ?: documentDao.getAllDocuments().firstOrNull { it.fileName.equals(item.name, ignoreCase = true) }

                if (doc != null) {
                    Log.i(tag, "Read article detected on CrossPoint in $folder: ${item.name}. Cleaning up remotely and archiving locally.")

                    val excerpt = if (!doc.excerpt.isNullOrBlank()) {
                        doc.excerpt
                    } else {
                        try {
                            DocumentPreviewReader.readPreviewText(doc).take(300)
                        } catch (_: Exception) {
                            null
                        }
                    }

                    // 1. Delete remote file from the read folder on CrossPoint
                    val remoteDeleted = webDavClient.deleteFile("${folder.trimEnd('/')}/${item.name}")

                    // 2. Delete local file from phone library, freeing 100% of the file space
                    val localFile = File(doc.localPath)
                    if (localFile.exists()) {
                        localFile.delete()
                    }

                    // 3. Instead of permanently deleting, preserve item in 90-day archive
                    documentDao.markArchived(doc.id, System.currentTimeMillis(), excerpt)
                    cleanedCount++
                }
            }
        }
        return cleanedCount
    }

    /**
     * Synchronizes the hidden manifest (/crosssend/.crosssend-sync.json) with CrossPoint
     * so multiple CrossSend devices/clients can share article metadata, fetch missing URLs
     * locally, and propagate deletions/archiving via tombstones.
     */
    private suspend fun reconcileSyncManifest(): Pair<Int, Int> {
        var manifestUploaded = 0
        var manifestDeleted = 0
        try {
            val rawJson = webDavClient.readTextFile(SyncManifestCodec.REMOTE_MANIFEST_PATH)
            val remoteManifest = rawJson?.let { SyncManifestCodec.parse(it) }

            if (remoteManifest != null && remoteManifest.entries.isNotEmpty()) {
                val localDocs = documentDao.getAllDocuments()
                val existingRemoteArticleNames = webDavClient.listDirectory("/crosssend/articles")
                    .filter { !it.isDirectory }
                    .map { it.name }
                    .toSet()

                for (entry in remoteManifest.entries) {
                    val localDoc = entry.sourceUrl?.let { url ->
                        localDocs.firstOrNull { it.sourceUrl == url }
                    } ?: localDocs.firstOrNull { doc ->
                        (entry.remotePath.isNotBlank() && doc.remotePath == entry.remotePath) ||
                            (entry.fileName.isNotBlank() && doc.fileName.equals(entry.fileName, ignoreCase = true))
                    }

                    when (entry.status) {
                        SyncManifestStatus.ARCHIVED -> {
                            if (localDoc != null &&
                                localDoc.syncStatus != SyncStatus.ARCHIVED &&
                                localDoc.syncStatus != SyncStatus.PENDING_DELETE &&
                                localDoc.syncStatus != SyncStatus.PENDING_UPLOAD &&
                                entry.updatedAt >= localDoc.createdAt
                            ) {
                                val localFile = File(localDoc.localPath)
                                if (localFile.exists()) {
                                    localFile.delete()
                                }
                                documentDao.markArchived(
                                    id = localDoc.id,
                                    archivedAt = entry.updatedAt,
                                    excerpt = localDoc.excerpt ?: entry.excerpt
                                )
                                if (localDoc.remotePath.isNotBlank()) {
                                    try {
                                        webDavClient.deleteFile(localDoc.remotePath)
                                    } catch (_: Exception) {}
                                }
                                manifestDeleted++
                            }
                        }

                        SyncManifestStatus.ACTIVE -> {
                            val remoteUrl = entry.sourceUrl
                            if (localDoc == null &&
                                remoteUrl != null &&
                                remoteUrl.startsWith("https://", ignoreCase = true) &&
                                onFetchRemoteArticle != null
                            ) {
                                val fetched = try {
                                    onFetchRemoteArticle.invoke(entry)
                                } catch (e: Exception) {
                                    Log.w(tag, "Failed to fetch remote manifest article $remoteUrl: ${e.message}")
                                    false
                                }
                                if (fetched) {
                                    val createdDoc = documentDao.findBySourceUrl(remoteUrl)
                                    if (createdDoc != null) {
                                        val alreadyOnReader = (entry.fileName.isNotBlank() &&
                                            existingRemoteArticleNames.any { it.equals(entry.fileName, ignoreCase = true) }) ||
                                            existingRemoteArticleNames.any { it.equals(createdDoc.fileName, ignoreCase = true) }

                                        if (alreadyOnReader) {
                                            val resolvedRemotePath = entry.remotePath.ifBlank { createdDoc.remotePath }
                                            documentDao.update(
                                                createdDoc.copy(
                                                    remotePath = resolvedRemotePath,
                                                    syncStatus = SyncStatus.SYNCED,
                                                    syncError = null
                                                )
                                            )
                                        } else {
                                            val localFile = File(createdDoc.localPath)
                                            if (localFile.exists() && webDavClient.uploadFile(localFile, createdDoc.remotePath)) {
                                                documentDao.markSynced(createdDoc.id)
                                                manifestUploaded++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val refreshedLocalEntries = documentDao.getAllDocuments()
                .mapNotNull { SyncManifestCodec.fromDocumentEntity(it) }
            val remoteEntries = remoteManifest?.entries.orEmpty()
            val mergedEntries = SyncManifestCodec.mergeEntries(refreshedLocalEntries, remoteEntries)

            if (mergedEntries != remoteEntries && (mergedEntries.isNotEmpty() || remoteManifest != null)) {
                val nextManifest = SyncManifest(
                    version = SyncManifest.CURRENT_VERSION,
                    updatedAt = System.currentTimeMillis(),
                    entries = mergedEntries
                )
                webDavClient.writeTextFile(
                    SyncManifestCodec.REMOTE_MANIFEST_PATH,
                    SyncManifestCodec.toJson(nextManifest)
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Manifest reconciliation failed: ${e.message}")
        }
        return manifestUploaded to manifestDeleted
    }
}
