package com.crosssend.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.util.EpubCoverLoader
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the 90-day archive screen, managing archived document search, view mode,
 * batch deletion, and resending archived web articles to the reader.
 */
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CrossSendApp
        get() = getApplication()

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val documentDao = app.database.documentDao()

    private val allArchivedFlow = documentDao.getArchivedDocuments()

    val totalArchivedCount: StateFlow<Int> = allArchivedFlow
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchVisible = MutableStateFlow(false)
    val isSearchVisible: StateFlow<Boolean> = _isSearchVisible.asStateFlow()

    private val _isGridView = MutableStateFlow(prefs.getBoolean(KEY_ARCHIVE_IS_GRID_VIEW, true))
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UiMessage> = _uiEvents.asSharedFlow()

    val archivedDocuments: StateFlow<List<DocumentEntity>> = combine(
        allArchivedFlow,
        _searchQuery
    ) { docs, query ->
        val q = query.trim()
        if (q.isBlank()) {
            docs
        } else {
            docs.filter { doc ->
                doc.title.contains(q, ignoreCase = true) ||
                    doc.fileName.contains(q, ignoreCase = true) ||
                    (doc.sourceUrl?.contains(q, ignoreCase = true) == true)
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleSearch() {
        if (_isSearchVisible.value) {
            closeSearch()
        } else {
            _isSearchVisible.value = true
        }
    }

    fun closeSearch() {
        _searchQuery.value = ""
        _isSearchVisible.value = false
    }

    fun toggleGridView() {
        val next = !_isGridView.value
        _isGridView.value = next
        prefs.edit { putBoolean(KEY_ARCHIVE_IS_GRID_VIEW, next) }
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
        val allIds = archivedDocuments.value.map { it.id }.toSet()
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
            withContext(Dispatchers.IO) {
                documentDao.delete(doc)
                EpubCoverLoader.deleteSidecarCoverForDocument(app, doc.fileName)
            }
        }
    }

    fun deleteSelected() {
        val idsToDelete = _selectedIds.value.toList()
        clearSelection()
        if (idsToDelete.isEmpty()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (id in idsToDelete) {
                    val doc = documentDao.getById(id) ?: continue
                    documentDao.delete(doc)
                    EpubCoverLoader.deleteSidecarCoverForDocument(app, doc.fileName)
                }
            }
        }
    }

    fun clearAllArchive() {
        clearSelection()
        val docsToDelete = archivedDocuments.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val allArchived = docsToDelete.ifEmpty {
                    documentDao.getAllDocuments().filter { it.syncStatus == com.crosssend.app.data.model.SyncStatus.ARCHIVED }
                }
                for (doc in allArchived) {
                    documentDao.delete(doc)
                    EpubCoverLoader.deleteSidecarCoverForDocument(app, doc.fileName)
                }
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

    companion object {
        private const val PREFS_NAME = "crosssend_prefs"
        private const val KEY_ARCHIVE_IS_GRID_VIEW = "archive_is_grid_view"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
