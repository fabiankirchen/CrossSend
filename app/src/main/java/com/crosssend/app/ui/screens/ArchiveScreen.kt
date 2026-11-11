package com.crosssend.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.ui.components.DocumentCard
import com.crosssend.app.ui.components.DocumentCoverCard
import com.crosssend.app.ui.components.DocumentPreviewDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    val archivedDocuments by app.database.documentDao().getArchivedDocuments().collectAsState(initial = emptyList())

    var isGridView by remember { mutableStateOf(true) }
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedDocumentIds by remember { mutableStateOf(setOf<Long>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var documentToDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentForPreview by remember { mutableStateOf<DocumentEntity?>(null) }

    val documents = remember(archivedDocuments, searchQuery) {
        val q = searchQuery.trim()
        if (q.isBlank()) {
            archivedDocuments
        } else {
            archivedDocuments.filter { doc ->
                doc.title.contains(q, ignoreCase = true) ||
                    doc.fileName.contains(q, ignoreCase = true) ||
                    (doc.sourceUrl?.contains(q, ignoreCase = true) == true)
            }
        }
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = isSelectionMode || isSearchVisible || searchQuery.isNotEmpty()) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            if (isSelectionMode) {
                isSelectionMode = false
                selectedDocumentIds = emptySet()
            } else {
                searchQuery = ""
                isSearchVisible = false
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
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
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.main_action_close_selection)
                            )
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
                            Text(
                                if (allSelected) stringResource(R.string.main_selection_none)
                                else stringResource(R.string.main_selection_all)
                            )
                        }
                        IconButton(
                            enabled = selectedDocumentIds.isNotEmpty(),
                            onClick = { showBatchDeleteDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.main_action_delete_selected),
                                tint = if (selectedDocumentIds.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSearchVisible || searchQuery.isNotEmpty()) {
                                searchQuery = ""
                                isSearchVisible = false
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
                                text = pluralStringResource(R.plurals.archive_subtitle_count, archivedDocuments.size, archivedDocuments.size),
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
                                    searchQuery = ""
                                    isSearchVisible = false
                                } else {
                                    isSearchVisible = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.archive_cd_search)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { isGridView = !isGridView },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) {
                                    stringResource(R.string.main_view_list)
                                } else {
                                    stringResource(R.string.main_view_grid)
                                },
                                modifier = Modifier.size(19.dp)
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .height(46.dp)
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
                                    text = stringResource(R.string.archive_search_placeholder),
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
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
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
                } else if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 155.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            val isSelected = selectedDocumentIds.contains(doc.id)
                            DocumentCoverCard(
                                document = doc,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(documents, key = { it.id }) { doc ->
                            val isSelected = selectedDocumentIds.contains(doc.id)
                            DocumentCard(
                                document = doc,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
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
                        app.documentProcessor.processWebUrl(url, archivedDoc.title)
                    }
                    Toast.makeText(
                        context,
                        resources.getString(R.string.main_toast_resending_article),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (showBatchDeleteDialog && selectedDocumentIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_title_archive, selectedDocumentIds.size, selectedDocumentIds.size))
            },
            text = {
                Text(pluralStringResource(R.plurals.main_dialog_batch_delete_msg_archive, selectedDocumentIds.size, selectedDocumentIds.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idsToDelete = selectedDocumentIds.toList()
                        showBatchDeleteDialog = false
                        isSelectionMode = false
                        selectedDocumentIds = emptySet()

                        scope.launch {
                            withContext(Dispatchers.IO) {
                                for (id in idsToDelete) {
                                    val doc = app.database.documentDao().getById(id) ?: continue
                                    app.database.documentDao().delete(doc)
                                    File(context.filesDir, "library/covers/${doc.fileName}.jpg").delete()
                                }
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
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.database.documentDao().delete(toDelete)
                                File(context.filesDir, "library/covers/${toDelete.fileName}.jpg").delete()
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
}
