package com.crosssend.app.network

import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.util.DateUtils
import com.crosssend.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    companion object {
        private val ALLOWED_FEED_TAGS = setOf(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "blockquote", "pre", "code", "ul", "ol", "li",
            "strong", "b", "em", "i", "u", "s", "del", "sub", "sup",
            "a", "img", "figure", "figcaption",
            "table", "thead", "tbody", "tr", "th", "td",
            "div", "span"
        )

        private val TRACKER_HOST_SUFFIXES = setOf(
            "pixel.wp.com",
            "stats.wp.com",
            "scorecardresearch.com",
            "google-analytics.com",
            "analytics.google.com",
            "doubleclick.net",
            "pixel.quantserve.com",
            "bat.bing.com",
            "px.ads.linkedin.com",
            "analytics.twitter.com",
            "mc.yandex.ru",
            "trc.taboola.com",
            "tags.bluekai.com",
            "feedportal.com",
            "eotrx.substackcdn.com"
        )

        private val PIXEL_DIMENSION_REGEX = Regex("""(?:width|height)\s*:\s*(?:0|1|2)(?:px)?\b""", RegexOption.IGNORE_CASE)
    }

    internal fun isTrackingPixel(url: String, img: Element? = null): Boolean {
        val clean = url.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return true

        if (img != null) {
            val w = img.attr("width").trim().removeSuffix("px").toIntOrNull()
            val h = img.attr("height").trim().removeSuffix("px").toIntOrNull()
            if ((w != null && w <= 2) || (h != null && h <= 2)) {
                return true
            }
            val style = img.attr("style").lowercase(Locale.ROOT)
            if (style.contains("display:none") ||
                style.contains("display: none") ||
                style.contains("visibility:hidden") ||
                style.contains("visibility: hidden") ||
                PIXEL_DIMENSION_REGEX.containsMatchIn(style)
            ) {
                return true
            }
        }

        val parsed = runCatching { java.net.URI(clean) }.getOrNull()
        val host = parsed?.host?.lowercase(Locale.ROOT).orEmpty()
        val path = parsed?.path?.lowercase(Locale.ROOT).orEmpty()

        if (host.isNotEmpty() && TRACKER_HOST_SUFFIXES.any { suffix ->
                host == suffix || host.endsWith(".$suffix")
            }
        ) {
            return true
        }
        if ((host == "feeds.feedburner.com" || host == "rss.feedburner.com") &&
            (path.contains("/~r/") || path.contains("/~ff/") || path.endsWith("~4"))
        ) {
            return true
        }
        if (host.endsWith("facebook.com") && path.startsWith("/tr")) return true
        if (host.endsWith("list-manage.com") && path.contains("/track/open")) return true
        if (host.endsWith("substack.com") && path.startsWith("/o/")) return true

        return path.endsWith("/1x1.gif") ||
            path.endsWith("/1x1.png") ||
            path.endsWith("/spacer.gif") ||
            path.endsWith("/clear.gif") ||
            path.contains("/pixel.") ||
            path.contains("/beacon.")
    }

    suspend fun fetchFeed(feedUrl: String): RssFeed = withContext(Dispatchers.IO) {
        val targetFeedUrl = UrlSanitizer.stripTrackingParams(feedUrl)
        require(EInkImageOptimizer.isSafeHttpsExternalUrl(targetFeedUrl)) {
            try {
                com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_error_unsafe_url)
            } catch (_: Throwable) {
                "Invalid or internal feed URL blocked."
            }
        }
        val request = Request.Builder()
            .url(targetFeedUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 CrossSend/1.0")
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
        parseFeedXml(xml, targetFeedUrl)
    }

    private fun sanitizeFeedDocument(doc: org.jsoup.nodes.Document): String {
        doc.select(
            "script, style, iframe, form, button, input, select, textarea, nav, footer, aside, " +
                "noscript, object, embed, svg, math, video, audio, source, track, canvas, map, area, applet, base, meta, link"
        ).remove()

        for (el in doc.body().getAllElements().toList()) {
            if (el == doc.body() || el.parent() == null) continue
            val tag = el.tagName().lowercase(Locale.ROOT)
            if (tag == "img") {
                val rawSrc = el.attr("src").trim()
                if (!EInkImageOptimizer.isSafeHtmlUri(rawSrc, allowRelative = false) || isTrackingPixel(rawSrc, el)) {
                    el.remove()
                    continue
                }
                val cleanSrc = UrlSanitizer.stripTrackingParams(rawSrc)
                val alt = el.attr("alt")
                for (attr in el.attributes().asList()) {
                    el.removeAttr(attr.key)
                }
                el.attr("src", cleanSrc)
                if (alt.isNotBlank()) {
                    el.attr("alt", alt)
                }
                continue
            }

            if (tag !in ALLOWED_FEED_TAGS) {
                el.unwrap()
                continue
            }

            var cleanHref: String? = null
            for (attr in el.attributes().asList()) {
                val key = attr.key.lowercase(Locale.ROOT)
                if (tag == "a" && key == "href" && EInkImageOptimizer.isSafeHtmlUri(attr.value, allowRelative = true)) {
                    cleanHref = UrlSanitizer.stripTrackingParams(attr.value.trim())
                }
                el.removeAttr(attr.key)
            }
            if (cleanHref != null) {
                el.attr("href", cleanHref)
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
        val feedLink = channel.select("> link").text().trim().takeIf { it.isNotBlank() }?.let(UrlSanitizer::stripTrackingParams)
        val nextPageUrl = channel.select("atom\\:link[rel=next], atom10\\:link[rel=next], link[rel=next]").attr("href").trim().takeIf { it.isNotBlank() }

        val items = mutableListOf<RssItem>()
        val unnamedArticleFallback = try {
            com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_unnamed_article)
        } catch (_: Throwable) {
            "Untitled Article"
        }
        for (itemEl in doc.select("item")) {
            val title = itemEl.select("title").text().trim().ifBlank { unnamedArticleFallback }
            val link = UrlSanitizer.stripTrackingParams(itemEl.select("link").text().trim())
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
                val mediaType = media.attr("type").lowercase(Locale.ROOT)
                if (mediaType.startsWith("audio/") || mediaType.startsWith("video/")) continue
                if ((url.startsWith("http://") || url.startsWith("https://")) && !isTrackingPixel(url)) {
                    imageUrls.add(UrlSanitizer.stripTrackingParams(url))
                }
            }

            for (img in parsedContent.select("img").toList()) {
                val src = img.absUrl("src").ifBlank { img.attr("src") }
                if (isTrackingPixel(src, img)) {
                    img.remove()
                } else if (src.startsWith("http://") || src.startsWith("https://")) {
                    imageUrls.add(UrlSanitizer.stripTrackingParams(src))
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
        val feedLink = feed.select("> link[rel=alternate], > link").attr("href").trim().takeIf { it.isNotBlank() }?.let(UrlSanitizer::stripTrackingParams)
        val nextPageUrl = feed.select("> link[rel=next]").attr("href").trim().takeIf { it.isNotBlank() }

        val items = mutableListOf<RssItem>()
        val unnamedArticleFallback = try {
            com.crosssend.app.CrossSendApp.instance.getString(com.crosssend.app.R.string.feeds_unnamed_article)
        } catch (_: Throwable) {
            "Untitled Article"
        }
        for (entry in doc.select("entry")) {
            val title = entry.select("title").text().trim().ifBlank { unnamedArticleFallback }
            val rawLink = entry.select("link[rel=alternate]").attr("href").ifBlank { entry.select("link").attr("href") }.trim()
            val link = UrlSanitizer.stripTrackingParams(rawLink)
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
            for (img in parsedContent.select("img").toList()) {
                val src = img.absUrl("src").ifBlank { img.attr("src") }
                if (isTrackingPixel(src, img)) {
                    img.remove()
                } else if (src.startsWith("http://") || src.startsWith("https://")) {
                    imageUrls.add(UrlSanitizer.stripTrackingParams(src))
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
