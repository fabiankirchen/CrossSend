package com.crosssend.app.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentEntity

/**
 * Shared selection-mode top app bar used by both the active library stream and the archive screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentSelectionTopAppBar(
    selectedCount: Int,
    allSelected: Boolean,
    onCloseSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onRequestBatchDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onCloseSelection) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.main_action_close_selection)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.main_selection_count, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            TextButton(onClick = onToggleSelectAll) {
                Text(
                    if (allSelected) stringResource(R.string.main_selection_none)
                    else stringResource(R.string.main_selection_all)
                )
            }
            IconButton(
                enabled = selectedCount > 0,
                onClick = onRequestBatchDelete
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.main_action_delete_selected),
                    tint = if (selectedCount > 0) {
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
}

/**
 * Shared Material 3 search input field backed by [SearchBarDefaults.InputField] with automatic
 * focus clearing when the soft keyboard is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CrossSendSearchInputField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    placeholderText: String,
    modifier: Modifier = Modifier,
    onClearSearch: (() -> Unit)? = null
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

    Surface(
        shape = SearchBarDefaults.inputFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { focusManager.clearFocus(force = true) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = placeholderText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.main_search_hint)
                )
            },
            trailingIcon = if (textFieldState.text.isNotEmpty()) {
                {
                    IconButton(
                        onClick = {
                            textFieldState.clearText()
                            onClearSearch?.invoke()
                            focusManager.clearFocus(force = true)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.main_clear_search)
                        )
                    }
                }
            } else {
                null
            }
        )
    }
}

/**
 * Shared adaptive cover grid or compact list renderer for document streams.
 */
@Composable
fun DocumentGridOrList(
    documents: List<DocumentEntity>,
    isGridView: Boolean,
    isSelectionMode: Boolean,
    selectedDocumentIds: Set<Long>,
    onDocumentClick: (DocumentEntity) -> Unit,
    onDocumentLongClick: (DocumentEntity) -> Unit,
    onDeleteDocument: (DocumentEntity) -> Unit,
    modifier: Modifier = Modifier,
    uploadProgressMap: Map<Long, Float> = emptyMap(),
    gridContentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    listContentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
    onRetryDocument: ((DocumentEntity) -> Unit)? = null
) {
    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 155.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = gridContentPadding
        ) {
            items(
                items = documents,
                key = { it.id },
                contentType = { it.type }
            ) { doc ->
                val isSelected = selectedDocumentIds.contains(doc.id)
                DocumentCoverCard(
                    document = doc,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    uploadProgress = uploadProgressMap[doc.id],
                    onClick = { onDocumentClick(doc) },
                    onLongClick = { onDocumentLongClick(doc) },
                    onDelete = onDeleteDocument,
                    onRetry = onRetryDocument
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = listContentPadding
        ) {
            items(
                items = documents,
                key = { it.id },
                contentType = { it.type }
            ) { doc ->
                val isSelected = selectedDocumentIds.contains(doc.id)
                DocumentCard(
                    document = doc,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    uploadProgress = uploadProgressMap[doc.id],
                    onClick = { onDocumentClick(doc) },
                    onLongClick = { onDocumentLongClick(doc) },
                    onDelete = onDeleteDocument,
                    onRetry = onRetryDocument
                )
            }
        }
    }
}
