@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.crosssend.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.sync.RssSyncWorker
import com.crosssend.app.util.DateUtils
import com.crosssend.app.util.RemoteImageLoader
import org.jsoup.Jsoup
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedsTab(
    modifier: Modifier = Modifier,
    articles: List<FeedArticleEntity> = emptyList(),
    feeds: List<FeedEntity> = emptyList(),
    selectedFeed: FeedEntity? = null,
    searchQuery: String = "",
    isGridView: Boolean = false,
    isRefreshing: Boolean = false,
    savingArticleLinks: Set<String> = emptySet(),
    loadingMoreFeedIds: Set<Long> = emptySet(),
    feedPageByFeedId: Map<Long, Int> = emptyMap(),
    nextPageUrlByFeedId: Map<Long, String> = emptyMap(),
    savedArticleUrls: Set<String> = emptySet(),
    savedNormalizedTitles: Set<String> = emptySet(),
    previewArticle: FeedArticleEntity? = null,
    onRefresh: () -> Unit = {},
    onLoadMore: (FeedEntity) -> Unit = {},
    onSaveArticle: (FeedArticleEntity) -> Unit = {},
    onOpenArticlePreview: (FeedArticleEntity) -> Unit = {},
    onDismissArticlePreview: () -> Unit = {},
    onOpenOriginalUrl: (FeedArticleEntity) -> Unit = {},
    onOpenAddFeedDialog: () -> Unit = {},
    onClearSearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalLocale.current.platformLocale

    LaunchedEffect(Unit) {
        RssSyncWorker.schedulePeriodicSync(context)
    }

    val feedTitleMap = remember(feeds) {
        feeds.associate { it.id to it.title }
    }

    fun isArticleSaved(article: FeedArticleEntity): Boolean {
        val link = article.link.trim()
        if (link.isNotEmpty() && savedArticleUrls.contains(link)) {
            return true
        }
        val normalizedTitle = article.title.trim().lowercase(Locale.ROOT)
        return normalizedTitle.isNotEmpty() && savedNormalizedTitles.contains(normalizedTitle)
    }

    fun formatArticleDate(article: FeedArticleEntity): String? {
        return if (article.publishedTimestamp > 0L) {
            DateUtils.formatRelativeOrDate(Date(article.publishedTimestamp))
        } else {
            article.pubDate?.takeIf { it.isNotBlank() }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        if (feeds.isEmpty()) {
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
                    FilledTonalButton(
                        onClick = onOpenAddFeedDialog,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.feeds_btn_add_first))
                    }
                }
            }
        } else if (articles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRefreshing) {
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
                } else if (searchQuery.isNotBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.main_empty_search_title, searchQuery.trim()),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.main_empty_search_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        FilledTonalButton(
                            onClick = onClearSearch,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.feed_clear_search))
                        }
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
                        FilledTonalButton(
                            onClick = onRefresh,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.feeds_btn_refresh_now))
                        }
                    }
                }
            }
        } else if (isGridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 155.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
            ) {
                itemsIndexed(
                    items = articles,
                    key = { index, article ->
                        if (article.id != 0L) article.id else "${article.feedId}_${article.link}_$index"
                    }
                ) { _, article ->
                    val isSaving = savingArticleLinks.contains(article.link)
                    val isAlreadySaved = isArticleSaved(article)
                    val feedTitle = feedTitleMap[article.feedId] ?: resources.getString(R.string.feeds_default_feed_title)

                    Card(
                        onClick = { onOpenArticlePreview(article) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val firstImg = extractFirstImageUrl(article)
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

                                val displayDate = formatArticleDate(article)
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
                                            shape = MaterialTheme.shapes.extraSmall,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 36.dp)
                                                .minimumInteractiveComponentSize()
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
                                            shape = MaterialTheme.shapes.extraSmall,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 36.dp)
                                                .minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(stringResource(R.string.feeds_badge_saved), style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = { onSaveArticle(article) },
                                            shape = MaterialTheme.shapes.extraSmall,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .defaultMinSize(minHeight = 36.dp)
                                                .minimumInteractiveComponentSize()
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

                item(span = { GridItemSpan(maxLineSpan) }) {
                    FeedPaginationFooter(
                        selectedFeed = selectedFeed,
                        articleCount = articles.size,
                        isLoadingMore = selectedFeed != null && loadingMoreFeedIds.contains(selectedFeed.id),
                        hasNextPage = selectedFeed != null && nextPageUrlByFeedId[selectedFeed.id] != null,
                        currentPage = selectedFeed?.let { feedPageByFeedId[it.id] } ?: 1,
                        searchActive = searchQuery.isNotBlank(),
                        onLoadMore = { selectedFeed?.let(onLoadMore) }
                    )
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                itemsIndexed(
                    items = articles,
                    key = { index, article ->
                        if (article.id != 0L) article.id else "${article.feedId}_${article.link}_$index"
                    }
                ) { _, article ->
                    val isSaving = savingArticleLinks.contains(article.link)
                    val isAlreadySaved = isArticleSaved(article)
                    val feedTitle = feedTitleMap[article.feedId] ?: resources.getString(R.string.feeds_default_feed_title)

                    Card(
                        onClick = { onOpenArticlePreview(article) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.medium,
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        FeedSourceBadge(
                                            sourceTitle = feedTitle,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        val displayDate = formatArticleDate(article)
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

                                val firstImg = extractFirstImageUrl(article)
                                if (!firstImg.isNullOrBlank()) {
                                    RssArticleThumbnail(
                                        imageUrl = firstImg,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(MaterialTheme.shapes.small)
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
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
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
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(stringResource(R.string.feeds_btn_in_library), style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { onSaveArticle(article) },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
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

                item {
                    FeedPaginationFooter(
                        selectedFeed = selectedFeed,
                        articleCount = articles.size,
                        isLoadingMore = selectedFeed != null && loadingMoreFeedIds.contains(selectedFeed.id),
                        hasNextPage = selectedFeed != null && nextPageUrlByFeedId[selectedFeed.id] != null,
                        currentPage = selectedFeed?.let { feedPageByFeedId[it.id] } ?: 1,
                        searchActive = searchQuery.isNotBlank(),
                        onLoadMore = { selectedFeed?.let(onLoadMore) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }

    previewArticle?.let { article ->
        val feedTitle = feedTitleMap[article.feedId] ?: resources.getString(R.string.feeds_default_feed_title)
        val formattedDate = remember(article.publishedTimestamp, article.pubDate, locale) {
            when {
                article.publishedTimestamp > 0L -> {
                    val date = Date(article.publishedTimestamp)
                    val relative = DateUtils.formatRelativeOrDate(date)
                    val medium = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(date)
                    if (relative == medium) relative else "$relative · $medium"
                }
                !article.pubDate.isNullOrBlank() -> article.pubDate
                else -> null
            }
        }
        FeedArticlePreviewSheet(
            article = article,
            feedTitle = feedTitle,
            formattedDate = formattedDate,
            isSaving = savingArticleLinks.contains(article.link),
            isAlreadySaved = isArticleSaved(article),
            onDismiss = onDismissArticlePreview,
            onOpenOriginal = { onOpenOriginalUrl(article) },
            onSendToReader = { onSaveArticle(article) }
        )
    }
}

@Composable
private fun FeedPaginationFooter(
    selectedFeed: FeedEntity?,
    articleCount: Int,
    isLoadingMore: Boolean,
    hasNextPage: Boolean,
    currentPage: Int,
    searchActive: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (selectedFeed != null && !searchActive) {
            if (isLoadingMore) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.feeds_btn_loading_older), style = MaterialTheme.typography.bodyMedium)
                }
            } else if (articleCount < 90 && (hasNextPage || articleCount >= 10 || currentPage > 1)) {
                OutlinedButton(
                    onClick = onLoadMore,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.feeds_btn_load_older, articleCount))
                }
            } else {
                Text(
                    text = stringResource(R.string.feeds_all_articles_loaded, articleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = stringResource(R.string.feeds_all_articles_loaded, articleCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedArticlePreviewSheet(
    article: FeedArticleEntity,
    feedTitle: String,
    formattedDate: String?,
    isSaving: Boolean,
    isAlreadySaved: Boolean,
    onDismiss: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSendToReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val firstImg = remember(article.imageUrlsJoined) { extractFirstImageUrl(article) }
    val excerptText = remember(article.id, article.description, article.contentHtml, article.title) {
        extractArticleExcerpt(article)
    }
    val canOpenOriginal = remember(article.link) {
        val normalized = DocumentProcessor.normalizeExternalHttpsUrl(article.link)
        EInkImageOptimizer.isSafeHttpsExternalUrl(normalized)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FeedSourceBadge(
                    sourceTitle = feedTitle,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!formattedDate.isNullOrBlank()) {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
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

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = article.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            article.author?.takeIf { it.isNotBlank() }?.let { author ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.feeds_by_author, author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!firstImg.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                RssArticleThumbnail(
                    imageUrl = firstImg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = excerptText.ifBlank { resources.getString(R.string.feed_preview_excerpt_fallback) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (excerptText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenOriginal,
                    enabled = canOpenOriginal,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.feed_preview_open_original))
                }

                when {
                    isSaving -> {
                        FilledTonalButton(
                            onClick = { },
                            enabled = false,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.feeds_btn_saving))
                        }
                    }
                    isAlreadySaved -> {
                        FilledTonalButton(
                            onClick = { },
                            enabled = false,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                disabledContentColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.feed_preview_already_saved))
                        }
                    }
                    else -> {
                        Button(
                            onClick = onSendToReader,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.feed_preview_send_reader))
                        }
                    }
                }
            }
        }
    }
}

private fun extractFirstImageUrl(article: FeedArticleEntity): String? {
    return article.imageUrlsJoined
        .split("\n")
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun extractArticleExcerpt(article: FeedArticleEntity): String {
    val fromDescription = article.description?.trim().orEmpty()
    if (fromDescription.isNotBlank()) {
        return fromDescription
    }
    if (article.contentHtml.isBlank()) {
        return ""
    }
    val plainText = try {
        Jsoup.parseBodyFragment(article.contentHtml).text().trim()
    } catch (_: Exception) {
        ""
    }
    if (plainText.isBlank() || plainText.equals(article.title.trim(), ignoreCase = true)) {
        return ""
    }
    return if (plainText.length > 600) {
        plainText.take(600).trimEnd() + "…"
    } else {
        plainText
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
    onAddFeed: (url: String, customTitle: String) -> Unit,
    modifier: Modifier = Modifier,
    isAdding: Boolean = false
) {
    var feedUrl by remember { mutableStateOf("") }
    var customTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        modifier = modifier,
        title = { Text(stringResource(R.string.feeds_dialog_add_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
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
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    label = { Text(stringResource(R.string.feeds_dialog_add_name_label)) },
                    singleLine = true,
                    enabled = !isAdding,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onAddFeed(feedUrl, customTitle) },
                enabled = !isAdding,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                if (isAdding) {
                    LoadingIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.feeds_dialog_btn_subscribe))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isAdding,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.feeds_dialog_btn_cancel))
            }
        }
    )
}
