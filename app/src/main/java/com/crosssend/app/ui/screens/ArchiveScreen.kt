package com.crosssend.app.ui.screens

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.ui.components.CrossSendSearchInputField
import com.crosssend.app.ui.components.DocumentGridOrList
import com.crosssend.app.ui.components.DocumentPreviewDialog
import com.crosssend.app.ui.components.DocumentSelectionTopAppBar
import com.crosssend.app.ui.viewmodel.ArchiveViewModel
import com.crosssend.app.ui.viewmodel.resolve
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchiveViewModel = viewModel()
) {
    val resources = LocalResources.current

    val documents by viewModel.archivedDocuments.collectAsStateWithLifecycle()
    val totalArchivedCount by viewModel.totalArchivedCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isSearchVisible by viewModel.isSearchVisible.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()

    val searchTextFieldState = rememberTextFieldState(initialText = searchQuery)
    val searchBarState = rememberSearchBarState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(searchTextFieldState) {
        snapshotFlow { searchTextFieldState.text.toString() }
            .collect { query -> viewModel.onSearchQueryChange(query) }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { uiMessage ->
            snackbarHostState.showSnackbar(uiMessage.resolve(resources))
        }
    }

    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentForPreview by remember { mutableStateOf<DocumentEntity?>(null) }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = isSelectionMode || isSearchVisible || searchQuery.isNotEmpty()) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            if (isSelectionMode) {
                viewModel.clearSelection()
            } else {
                searchTextFieldState.clearText()
                viewModel.closeSearch()
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                val allIds = documents.map { it.id }.toSet()
                val allSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)

                DocumentSelectionTopAppBar(
                    selectedCount = selectedIds.size,
                    allSelected = allSelected,
                    onCloseSelection = { viewModel.clearSelection() },
                    onToggleSelectAll = { viewModel.toggleSelectAll() },
                    onRequestBatchDelete = { showBatchDeleteDialog = true }
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSearchVisible || searchQuery.isNotEmpty()) {
                                searchTextFieldState.clearText()
                                viewModel.closeSearch()
                            } else {
                                onNavigateBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.archive_cd_back)
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.archive_title),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pluralStringResource(R.plurals.archive_subtitle_count, totalArchivedCount, totalArchivedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (isSearchVisible) {
                                    searchTextFieldState.clearText()
                                }
                                viewModel.toggleSearch()
                            }
                        ) {
                            Icon(
                                imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.archive_cd_search)
                            )
                        }
                        if (totalArchivedCount > 0) {
                            IconButton(onClick = { showClearAllDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = stringResource(R.string.card_action_delete)
                                )
                            }
                        }
                        FilledTonalIconButton(
                            onClick = { viewModel.toggleGridView() },
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) {
                                    stringResource(R.string.main_view_list)
                                } else {
                                    stringResource(R.string.main_view_grid)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.03f)
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            AnimatedVisibility(visible = isSearchVisible && !isSelectionMode) {
                CrossSendSearchInputField(
                    textFieldState = searchTextFieldState,
                    searchBarState = searchBarState,
                    placeholderText = stringResource(R.string.archive_search_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    onClearSearch = { viewModel.onSearchQueryChange("") }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = if (searchQuery.isNotBlank()) Icons.Default.Search else Icons.Outlined.Inventory2,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    stringResource(R.string.main_empty_search_title, searchQuery)
                                } else {
                                    stringResource(R.string.main_empty_archive_title)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) {
                                    stringResource(R.string.main_empty_search_desc)
                                } else {
                                    stringResource(R.string.main_empty_archive_desc)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    DocumentGridOrList(
                        documents = documents,
                        isGridView = isGridView,
                        isSelectionMode = isSelectionMode,
                        selectedDocumentIds = selectedIds,
                        onDocumentClick = { doc ->
                            if (isSelectionMode) {
                                viewModel.toggleSelection(doc.id)
                            } else {
                                documentForPreview = doc
                            }
                        },
                        onDocumentLongClick = { doc ->
                            if (!isSelectionMode) {
                                viewModel.enterSelectionMode(doc.id)
                            }
                        },
                        onDeleteDocument = { documentToDelete = it }
                    )
                }
            }
        }
    }

    documentForPreview?.let { doc ->
        DocumentPreviewDialog(
            document = doc,
            onDismiss = { documentForPreview = null },
            onDelete = { toDelete ->
                documentForPreview = null
                documentToDelete = toDelete
            },
            onResendFromArchive = { archivedDoc ->
                viewModel.resendDocument(archivedDoc)
            }
        )
    }

    if (showBatchDeleteDialog && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_title_archive, selectedIds.size, selectedIds.size))
            },
            text = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_msg_archive, selectedIds.size, selectedIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteDialog = false
                        viewModel.deleteSelected()
                    }
                ) {
                    Text(stringResource(R.string.main_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.main_action_cancel))
                }
            }
        )
    }

    if (showClearAllDialog && totalArchivedCount > 0) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_title_archive, totalArchivedCount, totalArchivedCount))
            },
            text = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_msg_archive, totalArchivedCount, totalArchivedCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        viewModel.clearAllArchive()
                    }
                ) {
                    Text(stringResource(R.string.main_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.main_action_cancel))
                }
            }
        )
    }

    documentToDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = {
                Text(stringResource(R.string.main_dialog_delete_title_archive))
            },
            text = {
                Text(stringResource(R.string.main_dialog_delete_msg_archive, doc.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = doc
                        documentToDelete = null
                        viewModel.deleteDocument(toDelete)
                    }
                ) {
                    Text(stringResource(R.string.main_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToDelete = null }) {
                    Text(stringResource(R.string.main_action_cancel))
                }
            }
        )
    }
}
