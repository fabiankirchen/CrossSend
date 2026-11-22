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
import com.crosssend.app.data.local.FeedArticleEntity
import com.crosssend.app.data.local.FeedEntity
import com.crosssend.app.sync.RssSyncWorker
import kotlinx.coroutines.CancellationException
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
import org.jsoup.nodes.Entities
import java.util.Date
import java.util.Locale

/**
 * ViewModel for RSS/Atom feed subscriptions, cached feed articles, article preview,
 * pagination, and converting feed entries into EPUB or XTCH documents for CrossPoint.
 */
class FeedsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CrossSendApp
        get() = getApplication()

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val feedDao = app.database.feedDao()
    private val documentDao = app.database.documentDao()

    val feeds: StateFlow<List<FeedEntity>> = feedDao.getAllFeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val allCachedArticles: StateFlow<List<FeedArticleEntity>> = feedDao.getAllFeedArticlesFlow()
        .map { list ->
            list.distinctBy { it.id }
                .sortedWith(
                    compareByDescending<FeedArticleEntity> {
                        if (it.publishedTimestamp > 0L) it.publishedTimestamp else it.fetchedAt
                    }.thenByDescending { it.id }
                )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _selectedFeed = MutableStateFlow<FeedEntity?>(null)
    val selectedFeed: StateFlow<FeedEntity?> = _selectedFeed.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridView = MutableStateFlow(prefs.getBoolean(KEY_FEEDS_IS_GRID_VIEW, false))
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isAddingFeed = MutableStateFlow(false)
    val isAddingFeed: StateFlow<Boolean> = _isAddingFeed.asStateFlow()

    private val _previewArticle = MutableStateFlow<FeedArticleEntity?>(null)
    val previewArticle: StateFlow<FeedArticleEntity?> = _previewArticle.asStateFlow()

    private val _savingArticleLinks = MutableStateFlow<Set<String>>(emptySet())
    val savingArticleLinks: StateFlow<Set<String>> = _savingArticleLinks.asStateFlow()

    private val _loadingMoreFeedIds = MutableStateFlow<Set<Long>>(emptySet())
    val loadingMoreFeedIds: StateFlow<Set<Long>> = _loadingMoreFeedIds.asStateFlow()

    private val _feedPageByFeedId = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val feedPageByFeedId: StateFlow<Map<Long, Int>> = _feedPageByFeedId.asStateFlow()

    private val _nextPageUrlByFeedId = MutableStateFlow<Map<Long, String>>(emptyMap())
    val nextPageUrlByFeedId: StateFlow<Map<Long, String>> = _nextPageUrlByFeedId.asStateFlow()

    private val _disabledAutoSyncFeedIds = MutableStateFlow(loadDisabledAutoSyncIds())
    val disabledAutoSyncFeedIds: StateFlow<Set<Long>> = _disabledAutoSyncFeedIds.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UiMessage> = _uiEvents.asSharedFlow()

    val savedArticleUrls: StateFlow<Set<String>> = documentDao.getAllSourceUrls()
        .map { urls -> urls.map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    val savedNormalizedTitles: StateFlow<Set<String>> = documentDao.getAllTitles()
        .map { titles ->
            titles.map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    val articles: StateFlow<List<FeedArticleEntity>> = combine(
        allCachedArticles,
        _selectedFeed,
        _searchQuery
    ) { allArticles, selected, query ->
        val feedFiltered = if (selected == null) {
            allArticles
        } else {
            allArticles.filter { it.feedId == selected.id }
        }
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            feedFiltered
        } else {
            feedFiltered.filter { article ->
                article.title.contains(trimmedQuery, ignoreCase = true) ||
                    (article.description?.contains(trimmedQuery, ignoreCase = true) == true) ||
                    (article.author?.contains(trimmedQuery, ignoreCase = true) == true)
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun isArticleSaved(article: FeedArticleEntity): Boolean {
        val link = article.link.trim()
        if (link.isNotEmpty() && savedArticleUrls.value.contains(link)) {
            return true
        }
        val normalizedTitle = article.title.trim().lowercase(Locale.ROOT)
        return normalizedTitle.isNotEmpty() && savedNormalizedTitles.value.contains(normalizedTitle)
    }

    fun isAutoSyncEnabled(feedId: Long): Boolean = !_disabledAutoSyncFeedIds.value.contains(feedId)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun toggleGridView() {
        val next = !_isGridView.value
        _isGridView.value = next
        prefs.edit { putBoolean(KEY_FEEDS_IS_GRID_VIEW, next) }
    }

    fun selectFeed(feed: FeedEntity?) {
        _selectedFeed.value = feed
    }

    fun refreshIfStale() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            try {
                val currentFeeds = withContext(Dispatchers.IO) { feedDao.getAllFeedsList() }
                if (currentFeeds.isEmpty()) return@launch
                val tenMinutesAgo = System.currentTimeMillis() - STALE_THRESHOLD_MS
                if (currentFeeds.any { it.lastFetchedAt < tenMinutesAgo }) {
                    _isRefreshing.value = true
                    withContext(Dispatchers.IO) {
                        RssSyncWorker.syncAllFeeds(app)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent background refresh
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshFeeds() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                withContext(Dispatchers.IO) {
                    val currentFeed = _selectedFeed.value
                    if (currentFeed == null) {
                        RssSyncWorker.syncAllFeeds(app)
                    } else {
                        RssSyncWorker.syncFeed(app, currentFeed)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.feeds_toast_refresh_error,
                        listOf(e.message ?: "")
                    )
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun addFeed(
        url: String,
        customTitle: String? = null,
        onSuccess: ((FeedEntity) -> Unit)? = null
    ) {
        val rawUrl = url.trim()
        if (rawUrl.isBlank()) {
            _uiEvents.tryEmit(UiMessage.Resource(R.string.feeds_toast_enter_url))
            return
        }
        val urlToFetch = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }
        if (_isAddingFeed.value) return

        viewModelScope.launch {
            _isAddingFeed.value = true
            try {
                val parsed = withContext(Dispatchers.IO) {
                    app.rssFeedParser.fetchFeed(urlToFetch)
                }
                val finalTitle = customTitle?.trim()?.takeIf { it.isNotBlank() } ?: parsed.title
                val entity = FeedEntity(
                    title = finalTitle,
                    url = urlToFetch,
                    lastFetchedAt = System.currentTimeMillis()
                )
                val id = withContext(Dispatchers.IO) {
                    val insertedId = feedDao.insert(entity)
                    if (parsed.items.isNotEmpty()) {
                        val entities = parsed.items.map { FeedArticleEntity.fromRssItem(insertedId, it) }
                        feedDao.upsertAndPruneFeed(insertedId, entities, System.currentTimeMillis(), MAX_ARTICLES_PER_FEED)
                    }
                    insertedId
                }
                val created = entity.copy(id = id)
                _uiEvents.emit(UiMessage.Resource(R.string.feeds_toast_subscribed_success, listOf(finalTitle)))
                onSuccess?.invoke(created)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.feeds_toast_fetch_error,
                        listOf(e.message ?: "")
                    )
                )
            } finally {
                _isAddingFeed.value = false
            }
        }
    }

    fun deleteFeed(feed: FeedEntity) {
        if (_selectedFeed.value?.id == feed.id) {
            _selectedFeed.value = null
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                feedDao.deleteArticlesForFeed(feed.id)
                feedDao.delete(feed)
            }
        }
    }

    fun toggleAutoSync(feed: FeedEntity) {
        val current = _disabledAutoSyncFeedIds.value
        val next = if (current.contains(feed.id)) current - feed.id else current + feed.id
        _disabledAutoSyncFeedIds.value = next
        prefs.edit {
            putStringSet(KEY_FEEDS_DISABLED_AUTO_SYNC_IDS, next.map { it.toString() }.toSet())
        }
    }

    fun loadMoreArticles(feed: FeedEntity) {
        if (_loadingMoreFeedIds.value.contains(feed.id)) return
        val nextPage = (_feedPageByFeedId.value[feed.id] ?: 1) + 1

        viewModelScope.launch {
            _loadingMoreFeedIds.value = _loadingMoreFeedIds.value + feed.id
            try {
                val olderFeed = withContext(Dispatchers.IO) {
                    app.rssFeedParser.fetchFeedPage(
                        feedUrl = feed.url,
                        page = nextPage,
                        nextPageUrl = _nextPageUrlByFeedId.value[feed.id]
                    )
                }

                if (olderFeed.items.isNotEmpty()) {
                    val entities = olderFeed.items.map { item ->
                        FeedArticleEntity.fromRssItem(feed.id, item)
                    }
                    withContext(Dispatchers.IO) {
                        feedDao.upsertAndPruneFeed(feed.id, entities, System.currentTimeMillis(), MAX_ARTICLES_PER_FEED)
                    }
                    _feedPageByFeedId.value = _feedPageByFeedId.value + (feed.id to nextPage)
                    if (!olderFeed.nextPageUrl.isNullOrBlank()) {
                        _nextPageUrlByFeedId.value = _nextPageUrlByFeedId.value + (feed.id to olderFeed.nextPageUrl)
                    }
                    _uiEvents.emit(
                        UiMessage.Plural(
                            R.plurals.feeds_toast_older_loaded,
                            olderFeed.items.size,
                            listOf(olderFeed.items.size)
                        )
                    )
                } else {
                    _uiEvents.emit(UiMessage.Resource(R.string.feeds_toast_no_more_older))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.feeds_toast_load_older_error,
                        listOf(e.message ?: "")
                    )
                )
            } finally {
                _loadingMoreFeedIds.value = _loadingMoreFeedIds.value - feed.id
            }
        }
    }

    fun openArticlePreview(article: FeedArticleEntity) {
        _previewArticle.value = article
        if (article.contentHtml.isBlank()) {
            viewModelScope.launch {
                val storedHtml = withContext(Dispatchers.IO) {
                    feedDao.getArticleContentHtml(article.id)
                }
                if (!storedHtml.isNullOrBlank() && _previewArticle.value?.id == article.id) {
                    _previewArticle.value = article.copy(contentHtml = storedHtml)
                }
            }
        }
    }

    fun dismissArticlePreview() {
        _previewArticle.value = null
    }

    fun sendArticleToReader(article: FeedArticleEntity, useOriginalLayout: Boolean = false) {
        val feedTitle = feeds.value.firstOrNull { it.id == article.feedId }?.title
            ?: app.resources.getString(R.string.feeds_default_feed_title)
        _savingArticleLinks.value = _savingArticleLinks.value + article.link

        app.applicationScope.launch {
            try {
                if (useOriginalLayout) {
                    val targetUrl = DocumentProcessor.normalizeExternalHttpsUrl(article.link)
                    if (EInkImageOptimizer.isSafeHttpsExternalUrl(targetUrl)) {
                        app.documentProcessor.processWebUrlOriginalLayout(
                            url = targetUrl,
                            fallbackTitle = article.title,
                            author = article.author ?: feedTitle,
                            publishedDate = if (article.publishedTimestamp > 0L) Date(article.publishedTimestamp) else null
                        )
                        return@launch
                    }
                }

                val fullHtml = withContext(Dispatchers.IO) {
                    feedDao.getArticleContentHtml(article.id)?.takeIf { it.isNotBlank() }
                        ?: article.contentHtml.takeIf { it.isNotBlank() }
                        ?: article.description?.takeIf { it.isNotBlank() }?.let { "<p>${Entities.escape(it)}</p>" }
                        ?: "<p>${Entities.escape(article.title)}</p>"
                }
                val fullArticle = article.copy(contentHtml = fullHtml)
                app.documentProcessor.processRssItem(fullArticle.toRssItem(), feedTitle)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.feeds_toast_save_error,
                        listOf(e.message ?: "")
                    )
                )
            } finally {
                _savingArticleLinks.value = _savingArticleLinks.value - article.link
            }
        }
    }

    fun deleteCachedArticle(article: FeedArticleEntity) {
        if (_previewArticle.value?.id == article.id) {
            _previewArticle.value = null
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                feedDao.deleteArticleById(article.id)
            }
        }
    }

    private fun loadDisabledAutoSyncIds(): Set<Long> {
        val raw = prefs.getStringSet(KEY_FEEDS_DISABLED_AUTO_SYNC_IDS, emptySet()).orEmpty()
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    companion object {
        private const val PREFS_NAME = "crosssend_prefs"
        private const val KEY_FEEDS_IS_GRID_VIEW = "feeds_is_grid_view"
        private const val KEY_FEEDS_DISABLED_AUTO_SYNC_IDS = "feeds_disabled_auto_sync_ids"
        private const val STALE_THRESHOLD_MS = 10L * 60 * 1000L
        private const val MAX_ARTICLES_PER_FEED = 90
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
