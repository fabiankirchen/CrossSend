@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.crosssend.app.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.sync.RssSyncWorker
import com.crosssend.app.util.DateUtils
import com.crosssend.app.util.RemoteImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedsTab(
    modifier: Modifier = Modifier,
    selectedFeed: FeedEntity? = null,
    onSelectFeed: (FeedEntity?) -> Unit = {},
    refreshTrigger: Long = 0L,
    onOpenAddFeedDialog: () -> Unit = {},
    isGridView: Boolean = false,
    feeds: List<FeedEntity>? = null,
    allCachedArticles: List<FeedArticleEntity>? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        RssSyncWorker.schedulePeriodicSync(context)
    }

    val effectiveFeeds = feeds ?: app.database.feedDao().getAllFeeds().collectAsState(initial = emptyList()).value
    val effectiveArticles = allCachedArticles ?: app.database.feedDao().getAllFeedArticlesFlow().collectAsState(initial = emptyList()).value

    val savedUrls by app.database.documentDao().getAllSourceUrls().collectAsState(initial = emptyList())
    val savedTitles by app.database.documentDao().getAllTitles().collectAsState(initial = emptyList())

    val feedPageMap = remember { mutableStateMapOf<Long, Int>() }
    val loadingMoreMap = remember { mutableStateMapOf<Long, Boolean>() }
    val nextPageUrlMap = remember { mutableStateMapOf<Long, String?>() }
    val savingArticlesMap = remember { mutableStateMapOf<String, Boolean>() }

    var localRefreshing by remember { mutableStateOf(false) }
    val effectiveRefreshing = isRefreshing || localRefreshing

    val handleRefresh: () -> Unit = {
        if (onRefresh != {}) {
            onRefresh()
        } else {
            scope.launch {
                localRefreshing = true
                try {
                    withContext(Dispatchers.IO) {
                        if (selectedFeed == null) {
                            RssSyncWorker.syncAllFeeds(app)
                        } else {
                            RssSyncWorker.syncFeed(app, selectedFeed)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Toast.makeText(context, resources.getString(R.string.feeds_toast_refresh_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
                } finally {
                    localRefreshing = false
                }
            }
        }
    }

    // Refresh when triggered from top app bar or parent trigger
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L) {
            handleRefresh()
        }
    }

    val feedTitleMap = remember(effectiveFeeds) {
        effectiveFeeds.associate { it.id to it.title }
    }

    // All cached articles chronologically sorted
    val sortedArticles = remember(effectiveArticles) {
        effectiveArticles
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<FeedArticleEntity> {
                    if (it.publishedTimestamp > 0L) it.publishedTimestamp else it.fetchedAt
                }.thenByDescending { it.id }
            )
    }

    // Filter by selectedFeed (if null, show all sources chronologically united)
    val displayArticles = remember(sortedArticles, selectedFeed) {
        if (selectedFeed == null) {
            sortedArticles
        } else {
            sortedArticles.filter { it.feedId == selectedFeed.id }
        }
    }

    fun loadMoreArticles(feed: FeedEntity) {
        if (loadingMoreMap[feed.id] == true) return
        val nextPage = (feedPageMap[feed.id] ?: 1) + 1

        scope.launch {
            loadingMoreMap[feed.id] = true
            try {
                val olderFeed = withContext(Dispatchers.IO) {
                    app.rssFeedParser.fetchFeedPage(feed.url, nextPage, nextPageUrlMap[feed.id])
                }

                if (olderFeed.items.isNotEmpty()) {
                    val entities = olderFeed.items.map { item ->
                        FeedArticleEntity.fromRssItem(feed.id, item)
                    }
                    withContext(Dispatchers.IO) {
                        app.database.feedDao().upsertAndPruneFeed(feed.id, entities, System.currentTimeMillis(), 90)
                    }
                    feedPageMap[feed.id] = nextPage
                    if (!olderFeed.nextPageUrl.isNullOrBlank()) {
                        nextPageUrlMap[feed.id] = olderFeed.nextPageUrl
                    }
                    Toast.makeText(
                        context,
                        resources.getQuantityString(R.plurals.feeds_toast_older_loaded, olderFeed.items.size, olderFeed.items.size),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(context, resources.getString(R.string.feeds_toast_no_more_older), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.feeds_toast_load_older_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } finally {
                loadingMoreMap[feed.id] = false
            }
        }
    }

    fun saveArticle(article: FeedArticleEntity) {
        val feedTitle = feedTitleMap[article.feedId] ?: resources.getString(R.string.feeds_default_feed_title)
        savingArticlesMap[article.link] = true
        app.applicationScope.launch {
            try {
                val fullHtml = withContext(Dispatchers.IO) {
                    app.database.feedDao().getArticleContentHtml(article.id)?.takeIf { it.isNotBlank() }
                        ?: article.contentHtml.takeIf { it.isNotBlank() }
                        ?: article.description?.takeIf { it.isNotBlank() }?.let { "<p>${org.jsoup.nodes.Entities.escape(it)}</p>" }
                        ?: "<p>${org.jsoup.nodes.Entities.escape(article.title)}</p>"
                }
                val fullArticle = article.copy(contentHtml = fullHtml)
                app.documentProcessor.processRssItem(fullArticle.toRssItem(), feedTitle)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, resources.getString(R.string.feeds_toast_save_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } finally {
                savingArticlesMap[article.link] = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = effectiveRefreshing,
        onRefresh = handleRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        if (effectiveFeeds.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.RssFeed,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.feeds_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.feeds_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(onClick = onOpenAddFeedDialog) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.feeds_btn_add_first))
                    }
                }
            }
        } else if (displayArticles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (effectiveRefreshing) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (selectedFeed != null) {
                                stringResource(R.string.feeds_loading_articles_feed, selectedFeed.title)
                            } else {
                                stringResource(R.string.feeds_loading_articles_all)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedFeed != null) {
                                stringResource(R.string.feeds_no_articles_in_feed)
                            } else {
                                stringResource(R.string.feeds_no_articles_cached)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(onClick = handleRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.feeds_btn_refresh_now))
                        }
                    }
                }
            }
        } else if (isGridView) {
            // Adaptive Cover Grid View (Android 17 large-screen / tablet / foldable ready)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 155.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                items(displayArticles, key = { if (it.id != 0L) it.id else "${it.feedId}_${it.link}" }) { article ->
                    val isSaving = savingArticlesMap[article.link] ?: false
                    val isAlreadySaved = savedUrls.contains(article.link) ||
                            savedTitles.any { it.equals(article.title.trim(), ignoreCase = true) }
                    val feedTitle = feedTitleMap[article.feedId] ?: stringResource(R.string.feeds_default_feed_title)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val firstImg = article.imageUrlsJoined.split("\n").map { it.trim() }.firstOrNull { it.isNotBlank() }
                            if (firstImg != null) {
                                RssArticleThumbnail(
                                    imageUrl = firstImg,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(115.dp)
                                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(90.dp)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RssFeed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                // Compact Feed Source Badge
                                FeedSourceBadge(
                                    sourceTitle = feedTitle,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )

                                Text(
                                    text = article.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                val displayDate = if (article.publishedTimestamp > 0L) {
                                    DateUtils.formatRelativeOrDate(Date(article.publishedTimestamp))
                                } else {
                                    article.pubDate
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!displayDate.isNullOrBlank()) {
                                        Text(
                                            text = displayDate,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.feeds_reading_time_min, article.readingTimeMinutes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (isSaving) {
                                        FilledTonalButton(
                                            onClick = { },
                                            enabled = false,
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            LoadingIndicator(
                                                modifier = Modifier.size(12.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("…", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else if (isAlreadySaved) {
                                        FilledTonalButton(
                                            onClick = { },
                                            enabled = false,
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                                disabledContentColor = MaterialTheme.colorScheme.primary
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(stringResource(R.string.feeds_badge_saved), style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { saveArticle(article) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(stringResource(R.string.feeds_badge_epub), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Pagination or end of list message
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedFeed != null) {
                            val isLoadingMore = loadingMoreMap[selectedFeed.id] ?: false
                            val hasNextPage = nextPageUrlMap[selectedFeed.id] != null
                            if (isLoadingMore) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LoadingIndicator(modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(stringResource(R.string.feeds_btn_loading_older), style = MaterialTheme.typography.bodyMedium)
                                }
                            } else if (displayArticles.size < 90 && (hasNextPage || displayArticles.size >= 10)) {
                                OutlinedButton(
                                    onClick = { loadMoreArticles(selectedFeed) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.feeds_btn_load_older, displayArticles.size))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.feeds_all_articles_loaded, displayArticles.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.feeds_all_articles_loaded, displayArticles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Generous bottom spacer to scroll past the floating bottom toolbar
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        } else {
            // List View with 68.dp rounded thumbnail & compact feed source badge
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                items(displayArticles, key = { if (it.id != 0L) it.id else "${it.feedId}_${it.link}" }) { article ->
                    val isSaving = savingArticlesMap[article.link] ?: false
                    val isAlreadySaved = savedUrls.contains(article.link) ||
                            savedTitles.any { it.equals(article.title.trim(), ignoreCase = true) }
                    val feedTitle = feedTitleMap[article.feedId] ?: stringResource(R.string.feeds_default_feed_title)

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    // Header row: Compact Feed Source Badge + Date + Reading time
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        FeedSourceBadge(
                                            sourceTitle = feedTitle,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        val displayDate = if (article.publishedTimestamp > 0L) {
                                            DateUtils.formatRelativeOrDate(Date(article.publishedTimestamp))
                                        } else {
                                            article.pubDate
                                        }
                                        if (!displayDate.isNullOrBlank()) {
                                            Text(
                                                text = "·",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = displayDate,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Text(
                                            text = stringResource(R.string.feeds_reading_time_min_bullet, article.readingTimeMinutes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = article.title,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (!article.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = article.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    article.author?.let { author ->
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = stringResource(R.string.feeds_by_author, author),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                val firstImg = article.imageUrlsJoined.split("\n").map { it.trim() }.firstOrNull { it.isNotBlank() }
                                if (!firstImg.isNullOrBlank()) {
                                    RssArticleThumbnail(
                                        imageUrl = firstImg,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSaving) {
                                    FilledTonalButton(
                                        onClick = { },
                                        enabled = false,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.feeds_btn_saving), style = MaterialTheme.typography.labelMedium)
                                    }
                                } else if (isAlreadySaved) {
                                    FilledTonalButton(
                                        onClick = { },
                                        enabled = false,
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                            disabledContentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.feeds_btn_in_library), style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { saveArticle(article) },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.feeds_btn_save), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                // Visible Pagination & 'Mehr laden' Control
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedFeed != null) {
                            val isLoadingMore = loadingMoreMap[selectedFeed.id] ?: false
                            val hasNextPage = nextPageUrlMap[selectedFeed.id] != null
                            if (isLoadingMore) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LoadingIndicator(modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(stringResource(R.string.feeds_btn_loading_older), style = MaterialTheme.typography.bodyMedium)
                                }
                            } else if (displayArticles.size < 90 && (hasNextPage || displayArticles.size >= 10)) {
                                OutlinedButton(
                                    onClick = { loadMoreArticles(selectedFeed) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.feeds_btn_load_older, displayArticles.size))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.feeds_all_articles_loaded, displayArticles.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.feeds_all_articles_loaded, displayArticles.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Generous bottom spacer to scroll past the floating bottom toolbar
                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

@Composable
fun FeedSourceBadge(
    sourceTitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = sourceTitle,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun RssArticleThumbnail(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (imageUrl.isNullOrBlank()) return
    val context = LocalContext.current
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUrl) {
        bitmap = RemoteImageLoader.loadThumbnail(context, imageUrl)
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.RssFeed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AddFeedDialog(
    onDismiss: () -> Unit,
    onFeedAdded: (FeedEntity) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    var feedUrl by remember { mutableStateOf("") }
    var customTitle by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feeds_dialog_add_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.feeds_dialog_add_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text(stringResource(R.string.feeds_dialog_add_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    label = { Text(stringResource(R.string.feeds_dialog_add_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val rawUrl = feedUrl.trim()
                    if (rawUrl.isBlank()) {
                        Toast.makeText(context, resources.getString(R.string.feeds_toast_enter_url), Toast.LENGTH_SHORT).show()
                        return@FilledTonalButton
                    }
                    val urlToFetch = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                        "https://$rawUrl"
                    } else rawUrl

                    isChecking = true
                    scope.launch {
                        try {
                            val parsed = withContext(Dispatchers.IO) {
                                app.rssFeedParser.fetchFeed(urlToFetch)
                            }
                            val finalTitle = if (customTitle.isNotBlank()) customTitle.trim() else parsed.title
                            val entity = FeedEntity(
                                title = finalTitle,
                                url = urlToFetch,
                                lastFetchedAt = System.currentTimeMillis()
                            )
                            val id = withContext(Dispatchers.IO) {
                                val insertedId = app.database.feedDao().insert(entity)
                                if (parsed.items.isNotEmpty()) {
                                    val entities = parsed.items.map { FeedArticleEntity.fromRssItem(insertedId, it) }
                                    app.database.feedDao().upsertAndPruneFeed(insertedId, entities, System.currentTimeMillis(), 90)
                                }
                                insertedId
                            }
                            val created = entity.copy(id = id)
                            onFeedAdded(created)
                            Toast.makeText(context, resources.getString(R.string.feeds_toast_subscribed_success, finalTitle), Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, resources.getString(R.string.feeds_toast_fetch_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking
            ) {
                if (isChecking) {
                    LoadingIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.feeds_dialog_btn_subscribe))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isChecking) {
                Text(stringResource(R.string.feeds_dialog_btn_cancel))
            }
        }
    )
}
