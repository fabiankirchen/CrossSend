package com.crosssend.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.crosssend.app.network.RssItem
import java.util.Date

@Entity(
    tableName = "feed_articles",
    indices = [Index(value = ["feedId", "link"], unique = true)]
)
data class FeedArticleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val feedId: Long,
    val link: String,
    val title: String,
    val author: String? = null,
    val pubDate: String? = null,
    val publishedTimestamp: Long = 0L,
    val description: String? = null,
    val contentHtml: String,
    val imageUrlsJoined: String = "",
    val wordCount: Int = 0,
    val readingTimeMinutes: Int = 1,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun toRssItem(): RssItem {
        val urls = if (imageUrlsJoined.isBlank()) {
            emptyList()
        } else {
            imageUrlsJoined.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        }
        return RssItem(
            title = title,
            link = link,
            author = author,
            pubDate = pubDate,
            publishedDate = if (publishedTimestamp > 0L) Date(publishedTimestamp) else null,
            description = description,
            contentHtml = contentHtml,
            imageUrls = urls,
            wordCount = wordCount,
            readingTimeMinutes = readingTimeMinutes
        )
    }

    companion object {
        fun fromRssItem(feedId: Long, item: RssItem): FeedArticleEntity {
            return FeedArticleEntity(
                feedId = feedId,
                link = item.link.ifBlank { "item://${feedId}/${item.title.hashCode()}" },
                title = item.title,
                author = item.author,
                pubDate = item.pubDate,
                publishedTimestamp = item.publishedDate?.time ?: 0L,
                description = item.description,
                contentHtml = item.contentHtml,
                imageUrlsJoined = item.imageUrls.joinToString("\n"),
                wordCount = item.wordCount,
                readingTimeMinutes = item.readingTimeMinutes,
                fetchedAt = System.currentTimeMillis()
            )
        }
    }
}
