package com.crosssend.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY addedAt DESC")
    fun getAllFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY addedAt DESC")
    suspend fun getAllFeedsList(): List<FeedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feed: FeedEntity): Long

    @Delete
    suspend fun delete(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE feeds SET lastFetchedAt = :timestamp WHERE id = :id")
    suspend fun updateLastFetched(id: Long, timestamp: Long)

    @Query("SELECT id, feedId, link, title, author, pubDate, publishedTimestamp, description, '' AS contentHtml, imageUrlsJoined, wordCount, readingTimeMinutes, fetchedAt FROM feed_articles WHERE feedId = :feedId ORDER BY CASE WHEN publishedTimestamp > 0 THEN publishedTimestamp ELSE fetchedAt END DESC, id DESC")
    fun getArticlesForFeedFlow(feedId: Long): Flow<List<FeedArticleEntity>>

    @Query("SELECT id, feedId, link, title, author, pubDate, publishedTimestamp, description, '' AS contentHtml, imageUrlsJoined, wordCount, readingTimeMinutes, fetchedAt FROM feed_articles WHERE feedId = :feedId ORDER BY CASE WHEN publishedTimestamp > 0 THEN publishedTimestamp ELSE fetchedAt END DESC, id DESC")
    suspend fun getArticlesForFeed(feedId: Long): List<FeedArticleEntity>

    @Query("SELECT id, feedId, link, title, author, pubDate, publishedTimestamp, description, '' AS contentHtml, imageUrlsJoined, wordCount, readingTimeMinutes, fetchedAt FROM feed_articles ORDER BY CASE WHEN publishedTimestamp > 0 THEN publishedTimestamp ELSE fetchedAt END DESC, id DESC")
    fun getAllFeedArticlesFlow(): Flow<List<FeedArticleEntity>>

    @Query("SELECT contentHtml FROM feed_articles WHERE id = :id LIMIT 1")
    suspend fun getArticleContentHtml(id: Long): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<FeedArticleEntity>)

    @Query(
        """
        DELETE FROM feed_articles 
        WHERE feedId = :feedId 
        AND id NOT IN (
            SELECT id FROM feed_articles 
            WHERE feedId = :feedId 
            ORDER BY CASE WHEN publishedTimestamp > 0 THEN publishedTimestamp ELSE fetchedAt END DESC, id DESC 
            LIMIT :maxCount
        )
        """
    )
    suspend fun pruneOldArticlesForFeed(feedId: Long, maxCount: Int = 90)

    @Transaction
    suspend fun upsertAndPruneFeed(feedId: Long, articles: List<FeedArticleEntity>, fetchedAt: Long, maxCount: Int = 90) {
        if (articles.isNotEmpty()) {
            insertArticles(articles)
            pruneOldArticlesForFeed(feedId, maxCount)
        }
        updateLastFetched(feedId, fetchedAt)
    }

    @Query("DELETE FROM feed_articles WHERE feedId = :feedId")
    suspend fun deleteArticlesForFeed(feedId: Long)
}
