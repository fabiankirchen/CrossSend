package com.crosssend.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.sync.RssSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    var showAddFeedDialog by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var selectedFeed by remember { mutableStateOf<FeedEntity?>(null) }
    var isGridView by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var feedToDelete by remember { mutableStateOf<FeedEntity?>(null) }

    val feeds by app.database.feedDao().getAllFeeds().collectAsState(initial = emptyList())
    val allCachedArticles by app.database.feedDao().getAllFeedArticlesFlow().collectAsState(initial = emptyList())

    fun doRefresh() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            try {
                withContext(Dispatchers.IO) {
                    val currentFeed = selectedFeed
                    if (currentFeed == null) {
                        RssSyncWorker.syncAllFeeds(app)
                    } else {
                        RssSyncWorker.syncFeed(app, currentFeed)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(context, resources.getString(R.string.feeds_toast_refresh_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val currentFeeds = app.database.feedDao().getAllFeedsList()
                if (currentFeeds.isNotEmpty()) {
                    val tenMinutesAgo = System.currentTimeMillis() - 10 * 60 * 1000L
                    val hasStale = currentFeeds.any { it.lastFetchedAt < tenMinutesAgo }
                    if (hasStale) {
                        withContext(Dispatchers.Main) { isRefreshing = true }
                        RssSyncWorker.syncAllFeeds(app)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent background refresh
            } finally {
                withContext(Dispatchers.Main) { isRefreshing = false }
            }
        }
    }

    // Intercept back gesture when a specific feed is filtered (Predictive Back)
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = selectedFeed != null) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            selectedFeed = null
        } catch (_: CancellationException) {
            backProgress = 0f
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.feeds_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedFeed == null) {
                                stringResource(R.string.feeds_subtitle_all)
                            } else {
                                stringResource(R.string.feeds_subtitle_filter, selectedFeed!!.title)
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
                                selectedFeed = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectedFeed != null) stringResource(R.string.feeds_cd_reset_filter) else stringResource(R.string.feeds_cd_back_to_library)
                        )
                    }
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) stringResource(R.string.feeds_cd_list_view) else stringResource(R.string.feeds_cd_grid_view),
                            modifier = Modifier.size(19.dp)
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
                onResetFilter = { selectedFeed = null }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.04f)
                    scaleX = scale
                    scaleY = scale
                }
        ) {
            FeedsTab(
                modifier = Modifier.fillMaxSize(),
                selectedFeed = selectedFeed,
                onSelectFeed = { selectedFeed = it },
                isGridView = isGridView,
                feeds = feeds,
                allCachedArticles = allCachedArticles,
                isRefreshing = isRefreshing,
                onRefresh = { doRefresh() },
                onOpenAddFeedDialog = { showAddFeedDialog = true }
            )

            if (showSourcesSheet) {
                FeedsSourcesSheet(
                    feeds = feeds,
                    allCachedArticles = allCachedArticles,
                    selectedFeed = selectedFeed,
                    onSelectFeed = { feed ->
                        selectedFeed = feed
                    },
                    onDismiss = { showSourcesSheet = false },
                    onOpenAddFeedDialog = { showAddFeedDialog = true },
                    onDeleteFeed = { feed -> feedToDelete = feed }
                )
            }

            if (showAddFeedDialog) {
                AddFeedDialog(
                    onDismiss = { showAddFeedDialog = false },
                    onFeedAdded = { }
                )
            }

            // Delete Feed Confirmation Dialog
            feedToDelete?.let { feed ->
                AlertDialog(
                    onDismissRequest = { feedToDelete = null },
                    title = { Text(stringResource(R.string.feeds_dialog_delete_title)) },
                    text = { Text(stringResource(R.string.feeds_dialog_delete_message, feed.title)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.database.feedDao().deleteArticlesForFeed(feed.id)
                                        app.database.feedDao().delete(feed)
                                    }
                                }
                                if (selectedFeed?.id == feed.id) {
                                    selectedFeed = null
                                }
                                feedToDelete = null
                            }
                        ) {
                            Text(stringResource(R.string.feeds_action_remove), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { feedToDelete = null }) {
                            Text(stringResource(R.string.feeds_action_cancel))
                        }
                    }
                )
            }
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
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
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
    onDeleteFeed: (FeedEntity) -> Unit
) {
    val cachedCountByFeed = remember(allCachedArticles) {
        allCachedArticles.groupingBy { it.feedId }.eachCount()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            // Header-Zeile
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
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
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
                // Eintrag 1 (Immer ganz oben): Alle Quellen (Chronologisch)
                item {
                    val isAllSelected = selectedFeed == null
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                onSelectFeed(null)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(16.dp),
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
                                    .clip(RoundedCornerShape(12.dp))
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
                                    text = pluralStringResource(R.plurals.feeds_sheet_total_articles, allCachedArticles.size, allCachedArticles.size),
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

                // Subscribed Feeds
                items(feeds, key = { it.id }) { feed ->
                    val isFeedSelected = selectedFeed?.id == feed.id
                    val count = cachedCountByFeed[feed.id] ?: 0
                    val domain = feed.url.substringAfter("://").substringBefore('/')

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                onSelectFeed(feed)
                                onDismiss()
                            },
                        shape = RoundedCornerShape(16.dp),
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
                                .clip(RoundedCornerShape(12.dp))
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
                                    text = pluralStringResource(R.plurals.feeds_sheet_feed_item_subtitle, count, count, domain),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isFeedSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
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
