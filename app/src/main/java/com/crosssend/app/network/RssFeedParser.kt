package com.crosssend.app.network

import android.util.Log
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class RssFeed(
    val title: String,
    val description: String? = null,
    val link: String? = null,
    val nextPageUrl: String? = null,
    val items: List<RssItem> = emptyList()
)

data class RssItem(
    val title: String,
    val link: String,
    val author: String? = null,
    val pubDate: String? = null,
    val publishedDate: Date? = null,
    val description: String? = null,
    val contentHtml: String,
    val imageUrls: List<String> = emptyList(),
    val wordCount: Int = 0,
    val readingTimeMinutes: Int = 1
)

class RssFeedParser {
    private val tag = "RssFeedParser"

    suspend fun fetchFeed(feedUrl: String): RssFeed = withContext(Dispatchers.IO) {
        require(EInkImageOptimizer.isSafeHttpsExternalUrl(feedUrl)) {
            try {
                com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_error_unsafe_url)
            } catch (_: Throwable) {
                "Invalid or internal feed URL blocked."
            }
        }
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) CrossSend/1.0")
            .build()

        val client = EInkImageOptimizer.getSafeInternetClient(connectTimeoutSeconds = 15, readTimeoutSeconds = 20)
        val xml = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val msg = try {
                    com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_error_http, response.code)
                } catch (_: Throwable) {
                    "Could not fetch feed: HTTP ${response.code}"
                }
                throw IllegalStateException(msg)
            }

            val responseBody = response.body ?: throw IllegalStateException(
                try {
                    com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_error_empty)
                } catch (_: Throwable) {
                    "Feed response is empty."
                }
            )
            EInkImageOptimizer.readBoundedString(responseBody, 10L * 1024 * 1024)
        }
        parseFeedXml(xml, feedUrl)
    }

    private fun sanitizeFeedDocument(doc: org.jsoup.nodes.Document): String {
        doc.select(
            "script, style, iframe, form, button, input, nav, footer, aside, " +
                "noscript, object, embed, svg, math, video, audio, source, track, canvas, map, area, applet, base, meta, link"
        ).remove()
        for (img in doc.select("img")) {
            if (!EInkImageOptimizer.isSafeHtmlUri(img.attr("src"), allowRelative = false)) {
                img.remove()
            }
        }
        val uriAttributes = setOf("href", "src", "xlink:href", "formaction", "srcdoc", "action", "data")
        for (el in doc.body().getAllElements()) {
            if (el == doc.body()) continue
            val attrsToRemove = mutableListOf<String>()
            for (attr in el.attributes()) {
                val key = attr.key.lowercase(Locale.ROOT)
                if (key == "style" || key.startsWith("on")) {
                    attrsToRemove.add(attr.key)
                } else if (key in uriAttributes) {
                    val isSafe = key != "srcdoc" &&
                        EInkImageOptimizer.isSafeHtmlUri(attr.value, allowRelative = true)
                    if (!isSafe) {
                        attrsToRemove.add(attr.key)
                    }
                }
            }
            for (k in attrsToRemove) {
                el.removeAttr(k)
            }
        }
        return doc.body().html().take(250_000)
    }

    suspend fun fetchFeedPage(feedUrl: String, page: Int, nextPageUrl: String? = null): RssFeed = withContext(Dispatchers.IO) {
        val targetUrl = when {
            !nextPageUrl.isNullOrBlank() -> nextPageUrl
            page > 1 -> {
                if (feedUrl.contains("?")) {
                    "$feedUrl&paged=$page"
                } else {
                    "$feedUrl?paged=$page"
                }
            }
            else -> feedUrl
        }

        try {
            fetchFeed(targetUrl)
        } catch (e: Exception) {
            if (page > 1 && nextPageUrl.isNullOrBlank()) {
                val altUrl = if (feedUrl.contains("?")) "$feedUrl&page=$page" else "$feedUrl?page=$page"
                fetchFeed(altUrl)
            } else {
                throw e
            }
        }
    }

    internal fun parseFeedXml(xml: String, feedUrl: String): RssFeed {
        val doc = Jsoup.parse(xml, feedUrl, org.jsoup.parser.Parser.xmlParser())
        val isAtom = doc.select("feed").isNotEmpty()

        return if (isAtom) {
            parseAtom(doc, feedUrl)
        } else {
            parseRss(doc, feedUrl)
        }
    }

    private fun parseRss(doc: org.jsoup.nodes.Document, feedUrl: String): RssFeed {
        val channel = doc.select("channel").first() ?: doc
        val feedTitle = channel.select("> title").text().trim().ifBlank { "RSS Feed" }
        val feedDesc = channel.select("> description").text().trim().takeIf { it.isNotBlank() }
        val feedLink = channel.select("> link").text().trim().takeIf { it.isNotBlank() }
        val nextPageUrl = channel.select("atom\\:link[rel=next], atom10\\:link[rel=next], link[rel=next]").attr("href").trim().takeIf { it.isNotBlank() }

        val items = mutableListOf<RssItem>()
        val unnamedArticleFallback = try {
            com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_unnamed_article)
        } catch (_: Throwable) {
            "Untitled Article"
        }
        for (itemEl in doc.select("item")) {
            val title = itemEl.select("title").text().trim().ifBlank { unnamedArticleFallback }
            val link = itemEl.select("link").text().trim()
            val author = itemEl.select("dc\\:creator, author").text().trim().takeIf { it.isNotBlank() }
            val pubDate = itemEl.select("pubDate").text().trim().takeIf { it.isNotBlank() }

            val contentEncoded = itemEl.select("content\\:encoded").text().trim()
            val description = itemEl.select("description").text().trim()

            val rawHtml = when {
                contentEncoded.isNotBlank() -> contentEncoded
                description.isNotBlank() -> description
                else -> "<p>${org.jsoup.nodes.Entities.escape(title)}</p>"
            }

            val parsedContent = Jsoup.parseBodyFragment(rawHtml)
            val imageUrls = mutableListOf<String>()

            for (media in itemEl.select("enclosure[url], media\\:content[url]")) {
                val url = media.attr("url")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    imageUrls.add(url)
                }
            }

            for (img in parsedContent.select("img")) {
                val src = img.absUrl("src").ifBlank { img.attr("src") }
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    imageUrls.add(src)
                }
            }

            val plainText = parsedContent.text()
            val wordCount = plainText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val readingTimeMinutes = maxOf(1, (wordCount / 210.0).roundToInt())

            val parsedDate = DateUtils.parseDate(pubDate)

            val cleanDesc = if (description.isNotBlank()) {
                val parsedText = Jsoup.parse(description).text().trim()
                if (parsedText.length > 500) {
                    parsedText.take(500).trimEnd() + "…"
                } else {
                    parsedText.takeIf { it.isNotBlank() }
                }
            } else null

            val safeContentHtml = sanitizeFeedDocument(parsedContent)

            items.add(
                RssItem(
                    title = title,
                    link = link,
                    author = author,
                    pubDate = pubDate,
                    publishedDate = parsedDate,
                    description = cleanDesc,
                    contentHtml = safeContentHtml,
                    imageUrls = imageUrls.distinct(),
                    wordCount = wordCount,
                    readingTimeMinutes = readingTimeMinutes
                )
            )
        }

        return RssFeed(
            title = feedTitle,
            description = feedDesc,
            link = feedLink,
            nextPageUrl = nextPageUrl,
            items = items
        )
    }

    private fun parseAtom(doc: org.jsoup.nodes.Document, feedUrl: String): RssFeed {
        val feed = doc.select("feed").first() ?: doc
        val feedTitle = feed.select("> title").text().trim().ifBlank { "Atom Feed" }
        val feedDesc = feed.select("> subtitle").text().trim().takeIf { it.isNotBlank() }
        val feedLink = feed.select("> link[rel=alternate], > link").attr("href").trim().takeIf { it.isNotBlank() }
        val nextPageUrl = feed.select("> link[rel=next]").attr("href").trim().takeIf { it.isNotBlank() }

        val items = mutableListOf<RssItem>()
        val unnamedArticleFallback = try {
            com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_unnamed_article)
        } catch (_: Throwable) {
            "Untitled Article"
        }
        for (entry in doc.select("entry")) {
            val title = entry.select("title").text().trim().ifBlank { unnamedArticleFallback }
            val link = entry.select("link[rel=alternate]").attr("href").ifBlank { entry.select("link").attr("href") }.trim()
            val author = entry.select("author > name").text().trim().takeIf { it.isNotBlank() }
            val pubDate = entry.select("published, updated").text().trim().takeIf { it.isNotBlank() }

            val content = entry.select("content").text().trim()
            val summary = entry.select("summary").text().trim()

            val rawHtml = when {
                content.isNotBlank() -> content
                summary.isNotBlank() -> summary
                else -> "<p>${org.jsoup.nodes.Entities.escape(title)}</p>"
            }

            val parsedContent = Jsoup.parseBodyFragment(rawHtml)
            val imageUrls = mutableListOf<String>()
            for (img in parsedContent.select("img")) {
                val src = img.absUrl("src").ifBlank { img.attr("src") }
                if (src.startsWith("http://") || src.startsWith("https://")) {
                    imageUrls.add(src)
                }
            }

            val plainText = parsedContent.text()
            val wordCount = plainText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
            val readingTimeMinutes = maxOf(1, (wordCount / 210.0).roundToInt())

            val parsedDate = DateUtils.parseDate(pubDate)

            val cleanSummary = if (summary.isNotBlank()) {
                val parsedText = Jsoup.parse(summary).text().trim()
                if (parsedText.length > 500) {
                    parsedText.take(500).trimEnd() + "…"
                } else {
                    parsedText.takeIf { it.isNotBlank() }
                }
            } else null

            val safeContentHtml = sanitizeFeedDocument(parsedContent)

            items.add(
                RssItem(
                    title = title,
                    link = link,
                    author = author,
                    pubDate = pubDate,
                    publishedDate = parsedDate,
                    description = cleanSummary,
                    contentHtml = safeContentHtml,
                    imageUrls = imageUrls.distinct(),
                    wordCount = wordCount,
                    readingTimeMinutes = readingTimeMinutes
                )
            )
        }

        return RssFeed(
            title = feedTitle,
            description = feedDesc,
            link = feedLink,
            nextPageUrl = nextPageUrl,
            items = items
        )
    }
}
