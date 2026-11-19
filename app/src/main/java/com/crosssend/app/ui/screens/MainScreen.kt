package com.crosssend.app.ui.screens

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
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
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.components.CrossSendBottomToolbar
import com.crosssend.app.ui.components.CrossSendSearchInputField
import com.crosssend.app.ui.components.DocumentGridOrList
import com.crosssend.app.ui.components.DocumentPreviewDialog
import com.crosssend.app.ui.components.DocumentSelectionTopAppBar
import com.crosssend.app.ui.components.QuickConnectionSettingsDialog
import com.crosssend.app.ui.viewmodel.DocumentSortOrder
import com.crosssend.app.ui.viewmodel.MainDocFilter
import com.crosssend.app.ui.viewmodel.MainViewModel
import com.crosssend.app.ui.viewmodel.resolve
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    onNavigateToFeeds: () -> Unit,
    onNavigateToDevice: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToArchive: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val unsentCount by viewModel.unsentCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatusText by viewModel.syncStatusText.collectAsStateWithLifecycle()
    val uploadProgressByDocId by viewModel.uploadProgressByDocId.collectAsStateWithLifecycle()

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

    // Dialog states
    var showSortMenu by remember { mutableStateOf(false) }
    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var showAddUrlDialog by remember { mutableStateOf(false) }
    var inputUrl by remember { mutableStateOf("") }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var documentForPreview by remember { mutableStateOf<DocumentEntity?>(null) }

    // Back press exits selection mode, search, or active filters (Predictive Back)
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(
        enabled = isSelectionMode || searchQuery.isNotEmpty() || selectedFilter != MainDocFilter.ALL
    ) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            when {
                isSelectionMode -> viewModel.clearSelection()
                searchQuery.isNotEmpty() -> {
                    searchTextFieldState.clearText()
                    viewModel.onSearchQueryChange("")
                }
                selectedFilter != MainDocFilter.ALL -> viewModel.onFilterChange(MainDocFilter.ALL)
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    // Note / Text state
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var noteTitle by remember { mutableStateOf("") }
    var noteContent by remember { mutableStateOf("") }

    // Native File Picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            viewModel.importFile(uri, mimeType)
        }
    }

    val sleepScreenPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setSleepScreen(uri)
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
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CrossSendSearchInputField(
                            textFieldState = searchTextFieldState,
                            searchBarState = searchBarState,
                            placeholderText = stringResource(R.string.main_search_placeholder),
                            modifier = Modifier.weight(1f),
                            onClearSearch = { viewModel.onSearchQueryChange("") }
                        )

                        val isConnected = connectionState is WifiConnectionState.ConnectedToCrossPointAp ||
                            connectionState is WifiConnectionState.ConnectedOnLocalNetwork
                        AnimatedVisibility(
                            visible = isConnected,
                            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                                scaleIn(initialScale = 0.8f, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                                scaleOut(targetScale = 0.8f, animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
                        ) {
                            val statusColors = com.crosssend.app.ui.theme.LocalStatusColors.current
                            FilledTonalIconButton(
                                onClick = onNavigateToDevice,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = statusColors.syncedContainer,
                                    contentColor = statusColors.onSyncedContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Devices,
                                    contentDescription = stringResource(R.string.main_nav_device),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.main_sort_order),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.main_sort_newest)) },
                                    leadingIcon = if (sortOrder == DocumentSortOrder.NEWEST) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        viewModel.onSortOrderChange(DocumentSortOrder.NEWEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.main_sort_oldest)) },
                                    leadingIcon = if (sortOrder == DocumentSortOrder.OLDEST) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        viewModel.onSortOrderChange(DocumentSortOrder.OLDEST)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.main_sort_title)) },
                                    leadingIcon = if (sortOrder == DocumentSortOrder.TITLE) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        viewModel.onSortOrderChange(DocumentSortOrder.TITLE)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        FilledTonalIconButton(
                            onClick = { viewModel.toggleGridView() }
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) stringResource(R.string.main_view_list) else stringResource(R.string.main_view_grid),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = onNavigateToArchive
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Inventory2,
                                contentDescription = stringResource(R.string.main_nav_archive),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                CrossSendBottomToolbar(
                    connectionState = connectionState,
                    isSyncing = isSyncing,
                    pendingUploadCount = unsentCount,
                    onConnect = { viewModel.connectOrDiscover() },
                    onOpenConnectionSettings = { showQuickSettingsDialog = true },
                    onSync = { viewModel.triggerSync() },
                    onOpenSettings = onNavigateToSettings,
                    onSelectFeeds = onNavigateToFeeds,
                    onAddUrl = { showAddUrlDialog = true },
                    onPickFile = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/pdf",
                                "text/markdown",
                                "text/plain",
                                "application/vnd.adobe.adept+xml",
                                "application/x-adobe-adept+xml",
                                "application/xml",
                                "text/xml",
                                "*/*"
                            )
                        )
                    },
                    onAddNote = { showAddNoteDialog = true },
                    onPickSleepScreenClick = { sleepScreenPickerLauncher.launch("image/*") }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAllSelected = selectedFilter == MainDocFilter.ALL
                FilterChip(
                    selected = isAllSelected,
                    onClick = { viewModel.onFilterChange(MainDocFilter.ALL) },
                    label = { Text(stringResource(R.string.main_filter_all)) },
                    leadingIcon = if (isAllSelected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
                FilterChip(
                    selected = selectedFilter == MainDocFilter.ARTICLES,
                    onClick = {
                        viewModel.onFilterChange(
                            if (selectedFilter == MainDocFilter.ARTICLES) MainDocFilter.ALL else MainDocFilter.ARTICLES
                        )
                    },
                    label = { Text(stringResource(R.string.main_filter_articles)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                FilterChip(
                    selected = selectedFilter == MainDocFilter.BOOKS,
                    onClick = {
                        viewModel.onFilterChange(
                            if (selectedFilter == MainDocFilter.BOOKS) MainDocFilter.ALL else MainDocFilter.BOOKS
                        )
                    },
                    label = { Text(stringResource(R.string.main_filter_books)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
                FilterChip(
                    selected = selectedFilter == MainDocFilter.UNSENT,
                    onClick = {
                        viewModel.onFilterChange(
                            if (selectedFilter == MainDocFilter.UNSENT) MainDocFilter.ALL else MainDocFilter.UNSENT
                        )
                    },
                    label = {
                        Text(
                            if (unsentCount > 0) {
                                stringResource(R.string.main_filter_unsynced_count, unsentCount)
                            } else {
                                stringResource(R.string.main_filter_unsynced)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }

            AnimatedVisibility(visible = isSyncing && syncStatusText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = syncStatusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
                                imageVector = when {
                                    searchQuery.isNotBlank() -> Icons.Default.Search
                                    selectedFilter == MainDocFilter.UNSENT -> Icons.Default.Sync
                                    selectedFilter == MainDocFilter.BOOKS -> Icons.AutoMirrored.Filled.MenuBook
                                    else -> Icons.AutoMirrored.Filled.Article
                                },
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> stringResource(R.string.main_empty_search_title, searchQuery)
                                    selectedFilter == MainDocFilter.UNSENT -> stringResource(R.string.main_empty_unsynced_title)
                                    selectedFilter == MainDocFilter.ARTICLES -> stringResource(
                                        R.string.main_empty_category_title,
                                        stringResource(R.string.main_filter_articles)
                                    )
                                    selectedFilter == MainDocFilter.BOOKS -> stringResource(
                                        R.string.main_empty_category_title,
                                        stringResource(R.string.main_filter_books)
                                    )
                                    else -> stringResource(R.string.main_empty_library_title)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = when {
                                    searchQuery.isNotBlank() -> stringResource(R.string.main_empty_search_desc)
                                    selectedFilter == MainDocFilter.UNSENT -> stringResource(R.string.main_empty_unsynced_desc)
                                    selectedFilter == MainDocFilter.ARTICLES -> stringResource(R.string.main_empty_articles_desc)
                                    selectedFilter == MainDocFilter.BOOKS -> stringResource(R.string.main_empty_books_desc)
                                    else -> stringResource(R.string.main_empty_library_desc)
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
                        uploadProgressMap = uploadProgressByDocId,
                        gridContentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 110.dp),
                        listContentPadding = PaddingValues(top = 4.dp, bottom = 110.dp),
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

    // Document Preview Dialog
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

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteDialog && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_title_files, selectedIds.size, selectedIds.size))
            },
            text = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_msg_files, selectedIds.size, selectedIds.size))
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

    // Quick Connection Settings Dialog
    if (showQuickSettingsDialog) {
        QuickConnectionSettingsDialog(
            onDismiss = { showQuickSettingsDialog = false },
            onConnectRequested = { viewModel.connectOrDiscover() }
        )
    }

    // Delete Confirmation Dialog (2-way sync)
    documentToDelete?.let { doc ->
        val isArchived = doc.syncStatus == SyncStatus.ARCHIVED
        AlertDialog(
            onDismissRequest = { documentToDelete = null },
            title = {
                Text(
                    if (isArchived) {
                        stringResource(R.string.main_dialog_delete_title_archive)
                    } else {
                        stringResource(R.string.main_dialog_delete_title_file)
                    }
                )
            },
            text = {
                Text(
                    if (isArchived) {
                        stringResource(R.string.main_dialog_delete_msg_archive, doc.title)
                    } else {
                        stringResource(R.string.main_dialog_delete_msg_file, doc.title)
                    }
                )
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

    // Web URL Dialog (Saves as Article)
    if (showAddUrlDialog) {
        val trimmedUrl = inputUrl.trim()
        val normalizedUrl = DocumentProcessor.normalizeExternalHttpsUrl(trimmedUrl)
        val isUrlValid = trimmedUrl.isNotEmpty() && EInkImageOptimizer.isSafeHttpsExternalUrl(normalizedUrl)
        AlertDialog(
            onDismissRequest = { showAddUrlDialog = false },
            modifier = Modifier.imePadding(),
            title = { Text(stringResource(R.string.main_dialog_add_url_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.main_dialog_add_url_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        label = { Text(stringResource(R.string.main_label_url_hint)) },
                        isError = trimmedUrl.isNotEmpty() && !isUrlValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trimmedUrl.isNotEmpty()) {
                        TextButton(
                            enabled = isUrlValid,
                            onClick = {
                                if (isUrlValid) {
                                    inputUrl = ""
                                    showAddUrlDialog = false
                                    viewModel.openUrlInBrowser(normalizedUrl)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.main_button_in_browser))
                        }
                    }
                    TextButton(
                        enabled = isUrlValid,
                        onClick = {
                            if (isUrlValid) {
                                inputUrl = ""
                                showAddUrlDialog = false
                                viewModel.addUrl(normalizedUrl)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.main_button_add))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddUrlDialog = false }) {
                    Text(stringResource(R.string.main_action_cancel))
                }
            }
        )
    }

    // Quick Note / Text Dialog (Saves as Article)
    if (showAddNoteDialog) {
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            modifier = Modifier.imePadding(),
            title = { Text(stringResource(R.string.main_dialog_add_note_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text(stringResource(R.string.main_label_note_title_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = noteContent,
                        onValueChange = { noteContent = it },
                        label = { Text(stringResource(R.string.main_label_note_content)) },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = noteContent.isNotBlank(),
                    onClick = {
                        val title = noteTitle.trim().ifBlank { null }
                        val content = noteContent.trim()
                        if (content.isNotEmpty()) {
                            noteTitle = ""
                            noteContent = ""
                            showAddNoteDialog = false
                            viewModel.addTextNote(title, content)
                        }
                    }
                ) {
                    Text(stringResource(R.string.main_button_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text(stringResource(R.string.main_action_cancel))
                }
            }
        )
    }
}

