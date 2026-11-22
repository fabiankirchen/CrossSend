package com.crosssend.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.WebArticleLoginRequest
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.model.SyncResult
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.sync.SyncUploadWorker
import com.crosssend.app.util.DateUtils
import com.crosssend.app.util.DocumentPreviewReader
import com.crosssend.app.util.EpubCoverLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class MainDocFilter {
    ALL,
    ARTICLES,
    BOOKS,
    UNSENT
}

enum class DocumentSortOrder {
    NEWEST,
    OLDEST,
    TITLE
}

/**
 * ViewModel for the main active document library stream, handling filtering, sorting,
 * selection mode, document creation/import, deletions, and reader synchronization.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CrossSendApp
        get() = getApplication()

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val documentDao = app.database.documentDao()

    val connectionState: StateFlow<WifiConnectionState> = app.wifiManager.connectionState
    val isSyncing: StateFlow<Boolean> = app.syncManager.isSyncing
    val syncStatusText: StateFlow<String> = app.syncManager.syncStatusText
    val uploadProgressByDocId: StateFlow<Map<Long, Float>> = app.syncManager.uploadProgressByDocId

    val unsentCount: StateFlow<Int> = documentDao.getPendingUploadCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    val failedCount: StateFlow<Int> = documentDao.getFailedCountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow(MainDocFilter.ALL)
    val selectedFilter: StateFlow<MainDocFilter> = _selectedFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(DocumentSortOrder.NEWEST)
    val sortOrder: StateFlow<DocumentSortOrder> = _sortOrder.asStateFlow()

    private val _isGridView = MutableStateFlow(prefs.getBoolean(KEY_MAIN_IS_GRID_VIEW, true))
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UiMessage> = _uiEvents.asSharedFlow()

    val documents: StateFlow<List<DocumentEntity>> = combine(
        documentDao.getAllVisibleDocuments(),
        _searchQuery,
        _selectedFilter,
        _sortOrder
    ) { docs, query, filter, sort ->
        val trimmedQuery = query.trim()
        val filtered = docs.filter { doc ->
            val matchesFilter = when (filter) {
                MainDocFilter.ALL -> true
                MainDocFilter.ARTICLES -> doc.type == DocumentType.ARTICLE
                MainDocFilter.BOOKS -> doc.type == DocumentType.BOOK
                MainDocFilter.UNSENT -> doc.syncStatus == SyncStatus.PENDING_UPLOAD ||
                    doc.syncStatus == SyncStatus.FAILED
            }
            val matchesQuery = trimmedQuery.isBlank() ||
                doc.title.contains(trimmedQuery, ignoreCase = true) ||
                doc.fileName.contains(trimmedQuery, ignoreCase = true) ||
                (doc.sourceUrl?.contains(trimmedQuery, ignoreCase = true) == true)
            matchesFilter && matchesQuery
        }

        val comparator = when (sort) {
            DocumentSortOrder.NEWEST -> compareByDescending<DocumentEntity> { effectiveTimestamp(it) }
                .thenByDescending { it.fileName }
            DocumentSortOrder.OLDEST -> compareBy<DocumentEntity> { effectiveTimestamp(it) }
                .thenBy { it.fileName }
            DocumentSortOrder.TITLE -> compareBy<DocumentEntity> { it.title.lowercase(Locale.ROOT) }
                .thenByDescending { effectiveTimestamp(it) }
        }
        filtered.sortedWith(comparator)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFilterChange(filter: MainDocFilter) {
        _selectedFilter.value = filter
    }

    fun onSortOrderChange(order: DocumentSortOrder) {
        _sortOrder.value = order
    }

    fun resetFilters() {
        _selectedFilter.value = MainDocFilter.ALL
        _searchQuery.value = ""
    }

    fun toggleGridView() {
        val next = !_isGridView.value
        _isGridView.value = next
        prefs.edit { putBoolean(KEY_MAIN_IS_GRID_VIEW, next) }
    }

    fun enterSelectionMode(docId: Long) {
        _isSelectionMode.value = true
        _selectedIds.value = setOf(docId)
    }

    fun toggleSelection(docId: Long) {
        val current = _selectedIds.value
        _selectedIds.value = if (current.contains(docId)) current - docId else current + docId
    }

    fun toggleSelectAll() {
        val allIds = documents.value.map { it.id }.toSet()
        _selectedIds.value = if (allIds.isNotEmpty() && _selectedIds.value.containsAll(allIds)) {
            emptySet()
        } else {
            allIds
        }
    }

    fun clearSelection() {
        _isSelectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun deleteDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            val hasSyncedDelete = withContext(Dispatchers.IO) {
                deleteOrArchiveDocument(doc)
            }
            if (hasSyncedDelete && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
                app.syncManager.performSync()
            }
        }
    }

    fun deleteSelected() {
        val idsToDelete = _selectedIds.value.toList()
        clearSelection()
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            val hasSyncedDeletes = withContext(Dispatchers.IO) {
                var syncedDeletes = false
                for (id in idsToDelete) {
                    val doc = documentDao.getById(id) ?: continue
                    if (deleteOrArchiveDocument(doc)) {
                        syncedDeletes = true
                    }
                }
                syncedDeletes
            }
            if (hasSyncedDeletes && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
                app.syncManager.performSync()
            }
        }
    }

    fun resendDocument(doc: DocumentEntity) {
        val url = doc.sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
        val normalizedUrl = DocumentProcessor.normalizeExternalHttpsUrl(url)
        if (!EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedUrl)) return

        app.applicationScope.launch {
            if (doc.renderMode == RenderMode.ORIGINAL_LAYOUT) {
                app.documentProcessor.processWebUrlOriginalLayout(
                    url = normalizedUrl,
                    fallbackTitle = doc.title,
                    author = doc.author,
                    existingDoc = doc
                )
            } else {
                app.documentProcessor.processWebUrl(normalizedUrl, doc.title)
            }
        }
        _uiEvents.tryEmit(UiMessage.Resource(R.string.main_toast_resending_article))
    }

    fun addUrl(url: String, renderMode: RenderMode? = null) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val normalized = DocumentProcessor.normalizeExternalHttpsUrl(trimmed)
        app.applicationScope.launch {
            if (renderMode == RenderMode.ORIGINAL_LAYOUT) {
                app.documentProcessor.processWebUrlOriginalLayout(normalized)
            } else {
                app.documentProcessor.processWebUrl(normalized)
            }
        }
    }

    fun openUrlInBrowser(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val normalized = DocumentProcessor.normalizeExternalHttpsUrl(trimmed)
        app.pendingWebViewRequest.value = WebArticleLoginRequest(normalized)
    }

    fun addTextNote(title: String?, body: String) {
        if (body.isBlank()) return
        val cleanTitle = title?.trim()?.ifBlank { null }
        app.applicationScope.launch {
            app.documentProcessor.processEmailOrText(cleanTitle, body)
        }
    }

    fun importFile(uri: Uri, mimeType: String? = null) {
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: app.contentResolver.getType(uri)
            ?: ""
        app.applicationScope.launch {
            app.documentProcessor.processFileUri(uri, resolvedMime)
        }
    }

    fun setSleepScreen(uri: Uri) {
        _uiEvents.tryEmit(UiMessage.Resource(R.string.main_toast_preparing_sleepscreen))
        app.applicationScope.launch {
            try {
                val doc = app.documentProcessor.processSleepScreenUri(uri)
                if (doc != null) {
                    _uiEvents.emit(UiMessage.Resource(R.string.main_snackbar_sleepscreen_transferred))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.main_snackbar_sleepscreen_error,
                        listOf(e.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    fun connectOrDiscover() {
        viewModelScope.launch {
            app.wifiManager.connectOrDiscover(app.webDavClient)
        }
    }

    fun cancelConnectionAttempt() {
        app.wifiManager.cancelConnectionAttempt()
    }

    fun triggerSync() {
        app.applicationScope.launch {
            when (val result = app.syncManager.performSync()) {
                is SyncResult.Success -> {
                    if (result.uploadedCount > 0 || result.deletedCount > 0) {
                        _uiEvents.emit(UiMessage.Resource(R.string.sync_status_completed))
                    }
                }
                is SyncResult.Queued -> {
                    _uiEvents.emit(UiMessage.Resource(R.string.main_sync_queued))
                }
                is SyncResult.Error -> {
                    val msg = result.message.trim()
                    if (msg.isNotEmpty()) {
                        _uiEvents.emit(UiMessage.Text(msg))
                    } else {
                        _uiEvents.emit(UiMessage.Resource(R.string.sync_error_unknown))
                    }
                }
            }
        }
    }

    fun retryDocument(doc: DocumentEntity) {
        app.applicationScope.launch {
            documentDao.updateSyncStatus(doc.id, SyncStatus.PENDING_UPLOAD, null)
            _uiEvents.emit(UiMessage.Resource(R.string.main_retry_queued))
            if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                when (val result = app.syncManager.performSync()) {
                    is SyncResult.Error -> {
                        val msg = result.message.trim()
                        if (msg.isNotEmpty()) {
                            _uiEvents.emit(UiMessage.Text(msg))
                        }
                    }
                    else -> Unit
                }
            } else {
                SyncUploadWorker.enqueue(app)
            }
        }
    }

    private suspend fun deleteOrArchiveDocument(doc: DocumentEntity): Boolean {
        return when (doc.syncStatus) {
            SyncStatus.ARCHIVED -> {
                documentDao.delete(doc)
                EpubCoverLoader.deleteSidecarCoverForDocument(app, doc.fileName)
                false
            }
            SyncStatus.SYNCED -> {
                val excerpt = doc.excerpt ?: DocumentPreviewReader.readPreviewText(doc)?.take(300)
                if (excerpt != null && doc.excerpt == null) {
                    documentDao.update(doc.copy(excerpt = excerpt))
                }
                documentDao.markPendingDelete(doc.id)
                true
            }
            SyncStatus.PENDING_UPLOAD, SyncStatus.FAILED, SyncStatus.PENDING_DELETE -> {
                val excerpt = doc.excerpt ?: DocumentPreviewReader.readPreviewText(doc)?.take(300)
                File(doc.localPath).delete()
                documentDao.markArchived(doc.id, System.currentTimeMillis(), excerpt)
                false
            }
        }
    }

    private fun effectiveTimestamp(doc: DocumentEntity): Long {
        return DateUtils.parseFilenameTimestamp(doc.fileName)?.time ?: doc.createdAt
    }

    companion object {
        private const val PREFS_NAME = "crosssend_prefs"
        private const val KEY_MAIN_IS_GRID_VIEW = "main_is_grid_view"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
