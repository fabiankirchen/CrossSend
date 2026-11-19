package com.crosssend.app.ui.screens

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.WebArticleLoginRequest
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.ui.viewmodel.FeedsViewModel
import com.crosssend.app.ui.viewmodel.resolve
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedsViewModel = viewModel()
) {
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddFeedDialog by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var feedToDelete by remember { mutableStateOf<FeedEntity?>(null) }

    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val articles by viewModel.articles.collectAsStateWithLifecycle()
    val allCachedArticles by viewModel.allCachedArticles.collectAsStateWithLifecycle()
    val selectedFeed by viewModel.selectedFeed.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isAddingFeed by viewModel.isAddingFeed.collectAsStateWithLifecycle()
    val previewArticle by viewModel.previewArticle.collectAsStateWithLifecycle()
    val savingArticleLinks by viewModel.savingArticleLinks.collectAsStateWithLifecycle()
    val loadingMoreFeedIds by viewModel.loadingMoreFeedIds.collectAsStateWithLifecycle()
    val feedPageByFeedId by viewModel.feedPageByFeedId.collectAsStateWithLifecycle()
    val nextPageUrlByFeedId by viewModel.nextPageUrlByFeedId.collectAsStateWithLifecycle()
    val disabledAutoSyncFeedIds by viewModel.disabledAutoSyncFeedIds.collectAsStateWithLifecycle()
    val savedArticleUrls by viewModel.savedArticleUrls.collectAsStateWithLifecycle()
    val savedNormalizedTitles by viewModel.savedNormalizedTitles.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, resources) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(resources))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshIfStale()
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(
        enabled = previewArticle != null || searchQuery.isNotEmpty() || selectedFeed != null
    ) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            when {
                previewArticle != null -> viewModel.dismissArticlePreview()
                searchQuery.isNotEmpty() -> {
                    viewModel.onSearchQueryChange("")
                    focusManager.clearFocus(force = true)
                }
                selectedFeed != null -> viewModel.selectFeed(null)
            }
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val activeFilter = selectedFeed
                    Column {
                        Text(
                            text = stringResource(R.string.feeds_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (activeFilter == null) {
                                stringResource(R.string.feeds_subtitle_all)
                            } else {
                                stringResource(R.string.feeds_subtitle_filter, activeFilter.title)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedFeed != null) {
                                viewModel.selectFeed(null)
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectedFeed != null) {
                                stringResource(R.string.feeds_cd_reset_filter)
                            } else {
                                stringResource(R.string.feeds_cd_back_to_library)
                            }
                        )
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = viewModel::toggleGridView,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) {
                                stringResource(R.string.feeds_cd_list_view)
                            } else {
                                stringResource(R.string.feeds_cd_grid_view)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            FeedsFilterFab(
                selectedFeed = selectedFeed,
                onOpenSourcesSheet = { showSourcesSheet = true },
                onResetFilter = { viewModel.selectFeed(null) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.04f)
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            AnimatedVisibility(visible = feeds.isNotEmpty() || searchQuery.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.main_search_hint),
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
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = {
                                    viewModel.onSearchQueryChange("")
                                    focusManager.clearFocus(force = true)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = resources.getString(R.string.feed_clear_search)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(force = true) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                FeedsTab(
                    modifier = Modifier.fillMaxSize(),
                    articles = articles,
                    feeds = feeds,
                    selectedFeed = selectedFeed,
                    searchQuery = searchQuery,
                    isGridView = isGridView,
                    isRefreshing = isRefreshing,
                    savingArticleLinks = savingArticleLinks,
                    loadingMoreFeedIds = loadingMoreFeedIds,
                    feedPageByFeedId = feedPageByFeedId,
                    nextPageUrlByFeedId = nextPageUrlByFeedId,
                    savedArticleUrls = savedArticleUrls,
                    savedNormalizedTitles = savedNormalizedTitles,
                    previewArticle = previewArticle,
                    onRefresh = viewModel::refreshFeeds,
                    onLoadMore = viewModel::loadMoreArticles,
                    onSaveArticle = { article -> viewModel.sendArticleToReader(article) },
                    onOpenArticlePreview = viewModel::openArticlePreview,
                    onDismissArticlePreview = viewModel::dismissArticlePreview,
                    onOpenOriginalUrl = { article ->
                        val normalized = DocumentProcessor.normalizeExternalHttpsUrl(article.link)
                        if (EInkImageOptimizer.isSafeHttpsExternalUrl(normalized)) {
                            viewModel.dismissArticlePreview()
                            CrossSendApp.instance.pendingWebViewRequest.value = WebArticleLoginRequest(
                                url = normalized,
                                title = article.title
                            )
                        }
                    },
                    onOpenAddFeedDialog = { showAddFeedDialog = true },
                    onClearSearch = {
                        viewModel.onSearchQueryChange("")
                        focusManager.clearFocus(force = true)
                    }
                )
            }
        }

        if (showSourcesSheet) {
            FeedsSourcesSheet(
                feeds = feeds,
                allCachedArticles = allCachedArticles,
                selectedFeed = selectedFeed,
                disabledAutoSyncFeedIds = disabledAutoSyncFeedIds,
                onSelectFeed = viewModel::selectFeed,
                onDismiss = { showSourcesSheet = false },
                onOpenAddFeedDialog = { showAddFeedDialog = true },
                onDeleteFeed = { feed -> feedToDelete = feed }
            )
        }

        if (showAddFeedDialog) {
            AddFeedDialog(
                onDismiss = {
                    if (!isAddingFeed) showAddFeedDialog = false
                },
                isAdding = isAddingFeed,
                onAddFeed = { url, customTitle ->
                    viewModel.addFeed(url, customTitle) {
                        showAddFeedDialog = false
                    }
                }
            )
        }

        feedToDelete?.let { feed ->
            AlertDialog(
                onDismissRequest = { feedToDelete = null },
                title = { Text(stringResource(R.string.feeds_dialog_delete_title)) },
                text = { Text(stringResource(R.string.feeds_dialog_delete_message, feed.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteFeed(feed)
                            feedToDelete = null
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.feeds_action_remove), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { feedToDelete = null },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.feeds_action_cancel))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedsFilterFab(
    selectedFeed: FeedEntity?,
    onOpenSourcesSheet: () -> Unit,
    onResetFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = selectedFeed,
        modifier = modifier.padding(bottom = 8.dp),
        transitionSpec = {
            fadeIn(animationSpec = effectsSpec) togetherWith fadeOut(animationSpec = effectsSpec)
        },
        label = "FeedsFilterFabState"
    ) { activeFeed ->
        if (activeFeed == null) {
            FloatingActionButton(
                onClick = onOpenSourcesSheet,
                modifier = Modifier.size(60.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = stringResource(R.string.feeds_cd_sources_and_filter),
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            Surface(
                modifier = Modifier.height(60.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
                tonalElevation = 4.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(60.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .height(60.dp)
                            .clickable(onClick = onOpenSourcesSheet)
                            .padding(start = 16.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = stringResource(R.string.feeds_cd_sources_and_filter),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = activeFeed.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }

                    IconButton(
                        onClick = onResetFilter,
                        modifier = Modifier.padding(end = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.feeds_cd_reset_filter),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsSourcesSheet(
    feeds: List<FeedEntity>,
    allCachedArticles: List<FeedArticleEntity>,
    selectedFeed: FeedEntity?,
    onSelectFeed: (FeedEntity?) -> Unit,
    onDismiss: () -> Unit,
    onOpenAddFeedDialog: () -> Unit,
    onDeleteFeed: (FeedEntity) -> Unit,
    modifier: Modifier = Modifier,
    disabledAutoSyncFeedIds: Set<Long> = emptySet()
) {
    val cachedCountByFeed = remember(allCachedArticles) {
        allCachedArticles.groupingBy { it.feedId }.eachCount()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.feeds_sheet_sources_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.feeds_sheet_sources_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalButton(
                    onClick = {
                        onDismiss()
                        onOpenAddFeedDialog()
                    },
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.feeds_sheet_btn_new), style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    val isAllSelected = selectedFeed == null
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                onSelectFeed(null)
                                onDismiss()
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isAllSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (isAllSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = null,
                                    tint = if (isAllSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.feeds_sheet_all_sources),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAllSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.feeds_sheet_total_articles,
                                        allCachedArticles.size,
                                        allCachedArticles.size
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isAllSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            if (isAllSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.feeds_cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                items(feeds, key = { it.id }) { feed ->
                    val isFeedSelected = selectedFeed?.id == feed.id
                    val count = cachedCountByFeed[feed.id] ?: 0
                    val domain = feed.url.substringAfter("://").substringBefore('/')
                    val isAutoSyncDisabled = disabledAutoSyncFeedIds.contains(feed.id)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                onSelectFeed(feed)
                                onDismiss()
                            },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isFeedSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (isFeedSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = null,
                                    tint = if (isFeedSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feed.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isFeedSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.feeds_sheet_feed_item_subtitle,
                                        count,
                                        count,
                                        domain
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isFeedSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    } else if (isAutoSyncDisabled) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }

                            if (isFeedSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.feeds_cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    onDeleteFeed(feed)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.feeds_cd_delete_feed),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
