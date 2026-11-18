package com.crosssend.app.ui.screens

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.WebArticleLoginRequest
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.SyncStatus
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.components.CrossSendBottomToolbar
import com.crosssend.app.ui.components.DocumentCard
import com.crosssend.app.ui.components.DocumentCoverCard
import com.crosssend.app.ui.components.DocumentPreviewDialog
import com.crosssend.app.ui.components.QuickConnectionSettingsDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    onNavigateToFeeds: () -> Unit,
    onNavigateToDevice: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToArchive: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    val connectionState by app.wifiManager.connectionState.collectAsState()
    val isSyncing by app.syncManager.isSyncing.collectAsState()
    val uploadProgressMap by app.syncManager.uploadProgressByDocId.collectAsState()

    val allVisibleDocuments by app.database.documentDao().getAllVisibleDocuments().collectAsState(initial = emptyList())
    val pendingUploadCount by app.database.documentDao().getPendingUploadCountFlow().collectAsState(initial = 0)

    // View mode: Grid (Covers) vs. List
    var isGridView by remember { mutableStateOf(true) }

    // Filter & search states
    var selectedTypeFilter by remember { mutableStateOf<DocumentType?>(null) }
    var onlyUnsynced by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val documents = remember(allVisibleDocuments, selectedTypeFilter, onlyUnsynced, searchQuery) {
        val q = searchQuery.trim()
        allVisibleDocuments.filter { doc ->
            val matchesType = selectedTypeFilter == null || doc.type == selectedTypeFilter
            val matchesUnsynced = !onlyUnsynced ||
                doc.syncStatus == SyncStatus.PENDING_UPLOAD ||
                doc.syncStatus == SyncStatus.FAILED
            val matchesQuery = q.isBlank() ||
                doc.title.contains(q, ignoreCase = true) ||
                doc.fileName.contains(q, ignoreCase = true) ||
                (doc.sourceUrl?.contains(q, ignoreCase = true) == true)
            matchesType && matchesUnsynced && matchesQuery
        }.sortedWith(
            compareByDescending<DocumentEntity> {
                com.crosssend.app.util.DateUtils.parseFilenameTimestamp(it.fileName)?.time ?: it.createdAt
            }.thenByDescending { it.fileName }
        )
    }

    // Dialog states
    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var showAddUrlDialog by remember { mutableStateOf(false) }
    var inputUrl by remember { mutableStateOf("") }

    // Multi-Selection state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedDocumentIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // Document Preview state
    var documentForPreview by remember { mutableStateOf<DocumentEntity?>(null) }

    // Back press exits selection mode, search, or active filters (Predictive Back)
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(
        enabled = isSelectionMode || searchQuery.isNotEmpty() || selectedTypeFilter != null || onlyUnsynced
    ) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            if (isSelectionMode) {
                isSelectionMode = false
                selectedDocumentIds = emptySet()
            } else if (searchQuery.isNotEmpty()) {
                searchQuery = ""
            } else {
                selectedTypeFilter = null
                onlyUnsynced = false
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
            val mimeType = context.contentResolver.getType(uri) ?: ""
            app.applicationScope.launch {
                app.documentProcessor.processFileUri(uri, mimeType)
            }
        }
    }

    val sleepScreenPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            Toast.makeText(context, resources.getString(R.string.main_toast_preparing_sleepscreen), Toast.LENGTH_SHORT).show()
            app.applicationScope.launch {
                try {
                    app.documentProcessor.processSleepScreenUri(uri)
                    snackbarHostState.showSnackbar(resources.getString(R.string.main_snackbar_sleepscreen_transferred))
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(resources.getString(R.string.main_snackbar_sleepscreen_error, e.localizedMessage ?: ""))
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                val allIds = documents.map { it.id }.toSet()
                val allSelected = allIds.isNotEmpty() && selectedDocumentIds.containsAll(allIds)

                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedDocumentIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.main_action_close_selection))
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.main_selection_count, selectedDocumentIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selectedDocumentIds = if (allSelected) emptySet() else allIds
                            }
                        ) {
                            Text(if (allSelected) stringResource(R.string.main_selection_none) else stringResource(R.string.main_selection_all))
                        }

                        IconButton(
                            enabled = selectedDocumentIds.isNotEmpty(),
                            onClick = { showBatchDeleteDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.main_action_delete_selected),
                                tint = if (selectedDocumentIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.main_search_hint),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    val focusManager = LocalFocusManager.current
                                    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
                                    var wasImeVisible by remember { mutableStateOf(false) }
                                    LaunchedEffect(imeBottomPx) {
                                        val isVisible = imeBottomPx > 0
                                        if (wasImeVisible && !isVisible) {
                                            focusManager.clearFocus(force = true)
                                        }
                                        wasImeVisible = isVisible
                                    }
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.main_search_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { focusManager.clearFocus(force = true) },
                                            onDone = { focusManager.clearFocus(force = true) }
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.main_clear_search),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

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
                                ),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Devices,
                                    contentDescription = stringResource(R.string.main_nav_device),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        FilledTonalIconButton(
                            onClick = { isGridView = !isGridView },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) stringResource(R.string.main_view_list) else stringResource(R.string.main_view_grid),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        FilledTonalIconButton(
                            onClick = onNavigateToArchive,
                            modifier = Modifier.size(44.dp)
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
                    pendingUploadCount = pendingUploadCount,
                    onConnect = {
                        scope.launch {
                            app.wifiManager.connectOrDiscover(app.webDavClient)
                        }
                    },
                    onOpenConnectionSettings = { showQuickSettingsDialog = true },
                    onSync = {
                        scope.launch {
                            app.syncManager.performSync()
                        }
                    },
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
                val isAllSelected = selectedTypeFilter == null && !onlyUnsynced
                FilterChip(
                    selected = isAllSelected,
                    onClick = {
                        selectedTypeFilter = null
                        onlyUnsynced = false
                    },
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
                    selected = selectedTypeFilter == DocumentType.ARTICLE,
                    onClick = {
                        selectedTypeFilter = if (selectedTypeFilter == DocumentType.ARTICLE) {
                            null
                        } else {
                            DocumentType.ARTICLE
                        }
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
                    selected = selectedTypeFilter == DocumentType.BOOK,
                    onClick = {
                        selectedTypeFilter = if (selectedTypeFilter == DocumentType.BOOK) {
                            null
                        } else {
                            DocumentType.BOOK
                        }
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
                    selected = onlyUnsynced,
                    onClick = { onlyUnsynced = !onlyUnsynced },
                    label = {
                        Text(
                            if (pendingUploadCount > 0) {
                                stringResource(R.string.main_filter_unsynced_count, pendingUploadCount)
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
                                    onlyUnsynced -> Icons.Default.Sync
                                    selectedTypeFilter == DocumentType.BOOK -> Icons.AutoMirrored.Filled.MenuBook
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
                                    onlyUnsynced -> stringResource(R.string.main_empty_unsynced_title)
                                    selectedTypeFilter == DocumentType.ARTICLE -> stringResource(
                                        R.string.main_empty_category_title,
                                        stringResource(R.string.main_filter_articles)
                                    )
                                    selectedTypeFilter == DocumentType.BOOK -> stringResource(
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
                                    onlyUnsynced -> stringResource(R.string.main_empty_unsynced_desc)
                                    selectedTypeFilter == DocumentType.ARTICLE -> stringResource(R.string.main_empty_articles_desc)
                                    selectedTypeFilter == DocumentType.BOOK -> stringResource(R.string.main_empty_books_desc)
                                    else -> stringResource(R.string.main_empty_library_desc)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (isGridView) {
                    // Adaptive Cover Grid View (Android 17 large-screen / tablet / foldable ready)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 155.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 110.dp)
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            val isSelected = selectedDocumentIds.contains(doc.id)
                            DocumentCoverCard(
                                document = doc,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                uploadProgress = uploadProgressMap[doc.id],
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedDocumentIds = if (isSelected) {
                                            selectedDocumentIds - doc.id
                                        } else {
                                            selectedDocumentIds + doc.id
                                        }
                                    } else {
                                        documentForPreview = doc
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedDocumentIds = setOf(doc.id)
                                    }
                                },
                                onDelete = { documentToDelete = it }
                            )
                        }
                    }
                } else {
                    // Compact List View
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp)
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            val isSelected = selectedDocumentIds.contains(doc.id)
                            DocumentCard(
                                document = doc,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                uploadProgress = uploadProgressMap[doc.id],
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedDocumentIds = if (isSelected) {
                                            selectedDocumentIds - doc.id
                                        } else {
                                            selectedDocumentIds + doc.id
                                        }
                                    } else {
                                        documentForPreview = doc
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedDocumentIds = setOf(doc.id)
                                    }
                                },
                                onDelete = { documentToDelete = it }
                            )
                        }
                    }
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
                archivedDoc.sourceUrl?.let { url ->
                    app.applicationScope.launch {
                        if (archivedDoc.renderMode == com.crosssend.app.data.model.RenderMode.ORIGINAL_LAYOUT) {
                            app.documentProcessor.processWebUrlOriginalLayout(
                                url = url,
                                fallbackTitle = archivedDoc.title,
                                author = archivedDoc.author,
                                existingDoc = archivedDoc
                            )
                        } else {
                            app.documentProcessor.processWebUrl(url, archivedDoc.title)
                        }
                    }
                    Toast.makeText(context, resources.getString(R.string.main_toast_resending_article), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Batch Delete Confirmation Dialog
    if (showBatchDeleteDialog && selectedDocumentIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_title_files, selectedDocumentIds.size, selectedDocumentIds.size))
            },
            text = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_msg_files, selectedDocumentIds.size, selectedDocumentIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedDocumentIds.toList()
                        showBatchDeleteDialog = false
                        isSelectionMode = false
                        selectedDocumentIds = emptySet()

                        scope.launch {
                            var hasSyncedDeletes = false
                            for (id in idsToDelete) {
                                val doc = app.database.documentDao().getById(id) ?: continue
                                when (doc.syncStatus) {
                                    SyncStatus.ARCHIVED -> {
                                        app.database.documentDao().delete(doc)
                                        File(context.filesDir, "library/covers/${doc.fileName}.jpg").delete()
                                    }
                                    SyncStatus.SYNCED -> {
                                        val excerpt = doc.excerpt ?: com.crosssend.app.util.DocumentPreviewReader.readPreviewText(doc)?.take(300)
                                        if (excerpt != null && doc.excerpt == null) {
                                            app.database.documentDao().update(doc.copy(excerpt = excerpt))
                                        }
                                        app.database.documentDao().markPendingDelete(doc.id)
                                        hasSyncedDeletes = true
                                    }
                                    SyncStatus.PENDING_UPLOAD, SyncStatus.FAILED, SyncStatus.PENDING_DELETE -> {
                                        val excerpt = doc.excerpt ?: com.crosssend.app.util.DocumentPreviewReader.readPreviewText(doc)?.take(300)
                                        File(doc.localPath).delete()
                                        app.database.documentDao().markArchived(doc.id, System.currentTimeMillis(), excerpt)
                                    }
                                }
                            }
                            if (hasSyncedDeletes && (app.wifiManager.isConnected() || app.webDavClient.isReachable())) {
                                app.syncManager.performSync()
                            }
                        }
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
            onConnectRequested = {
                scope.launch {
                    app.wifiManager.connectOrDiscover(app.webDavClient)
                }
            }
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
                        scope.launch {
                            when (toDelete.syncStatus) {
                                SyncStatus.ARCHIVED -> {
                                    app.database.documentDao().delete(toDelete)
                                    File(context.filesDir, "library/covers/${toDelete.fileName}.jpg").delete()
                                }
                                SyncStatus.SYNCED -> {
                                    val excerpt = toDelete.excerpt ?: com.crosssend.app.util.DocumentPreviewReader.readPreviewText(toDelete)?.take(300)
                                    if (excerpt != null && toDelete.excerpt == null) {
                                        app.database.documentDao().update(toDelete.copy(excerpt = excerpt))
                                    }
                                    app.database.documentDao().markPendingDelete(toDelete.id)
                                    if (app.wifiManager.isConnected() || app.webDavClient.isReachable()) {
                                        app.syncManager.performSync()
                                    }
                                }
                                SyncStatus.PENDING_UPLOAD, SyncStatus.FAILED, SyncStatus.PENDING_DELETE -> {
                                    val excerpt = toDelete.excerpt ?: com.crosssend.app.util.DocumentPreviewReader.readPreviewText(toDelete)?.take(300)
                                    File(toDelete.localPath).delete()
                                    app.database.documentDao().markArchived(toDelete.id, System.currentTimeMillis(), excerpt)
                                }
                            }
                        }
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
        AlertDialog(
            onDismissRequest = { showAddUrlDialog = false },
            title = { Text(stringResource(R.string.main_dialog_add_url_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.main_dialog_add_url_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        label = { Text("https://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inputUrl.isNotBlank()) {
                        TextButton(
                            onClick = {
                                val url = com.crosssend.app.converter.DocumentProcessor.normalizeExternalHttpsUrl(inputUrl)
                                inputUrl = ""
                                showAddUrlDialog = false
                                app.pendingWebViewRequest.value = WebArticleLoginRequest(url)
                            }
                        ) {
                            Text(stringResource(R.string.main_button_in_browser))
                        }
                    }
                    TextButton(
                        enabled = inputUrl.isNotBlank(),
                        onClick = {
                            val url = com.crosssend.app.converter.DocumentProcessor.normalizeExternalHttpsUrl(inputUrl)
                            inputUrl = ""
                            showAddUrlDialog = false
                            app.applicationScope.launch {
                                app.documentProcessor.processWebUrl(url)
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
            title = { Text(stringResource(R.string.main_dialog_add_note_title)) },
            text = {
                Column {
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
                        val content = noteContent
                        noteTitle = ""
                        noteContent = ""
                        showAddNoteDialog = false
                        app.applicationScope.launch {
                            app.documentProcessor.processEmailOrText(title, content)
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

