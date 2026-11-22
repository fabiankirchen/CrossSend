package com.crosssend.app.converter

import android.webkit.CookieManager
import com.crosssend.app.util.SafeLogger
import com.crosssend.app.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.util.Date
import java.util.Locale

data class ExtractedArticle(
    val title: String,
    val subtitle: String? = null,
    val author: String? = null,
    val siteName: String? = null,
    val leadImageUrl: String? = null,
    val contentHtml: String,
    val imageUrls: List<String> = emptyList(),
    val sourceUrl: String,
    val wordCount: Int = 0,
    val readingTimeMinutes: Int = 1,
    val publishedDate: Date? = null,
    val language: String? = null
)

class PaywallOrLoginRequiredException(val url: String, val articleTitle: String?) :
    Exception(
        if (Locale.getDefault().language == "de") {
            "Anmeldung erforderlich für: ${articleTitle ?: SafeLogger.redactUrl(url)}"
        } else {
            "Login required for: ${articleTitle ?: SafeLogger.redactUrl(url)}"
        }
    )

class ArticleExtractor {
    private val tag = "ArticleExtractor"

    private sealed interface FetchOutcome {
        data class Success(val html: String, val effectiveUrl: String) : FetchOutcome
        data class Unauthorized(val code: Int) : FetchOutcome
    }

    private fun getStatelessHttpClient(): OkHttpClient = EInkImageOptimizer.getSafeInternetClient(
        connectTimeoutSeconds = 20,
        readTimeoutSeconds = 25,
        followRedirects = true
    )

    private fun getCookieHttpClient(): OkHttpClient = EInkImageOptimizer.getSafeInternetClient(
        connectTimeoutSeconds = 20,
        readTimeoutSeconds = 25,
        followRedirects = true,
        extraNetworkInterceptor = sameOriginCookieInterceptor
    )

    private fun hasCookiesForUrl(url: String): Boolean = try {
        !CookieManager.getInstance().getCookie(url).isNullOrBlank()
    } catch (_: Throwable) {
        false
    }

    private fun buildArticleRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
        .header("Upgrade-Insecure-Requests", "1")
        .build()

    private fun executeFetch(client: OkHttpClient, targetUrl: String): FetchOutcome {
        return client.newCall(buildArticleRequest(targetUrl)).execute().use { response ->
            if (response.code in listOf(401, 403)) {
                return@use FetchOutcome.Unauthorized(response.code)
            }
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} from ${SafeLogger.redactUrl(targetUrl)}")
            }
            val html = response.body?.let { EInkImageOptimizer.readBoundedString(it, 10L * 1024 * 1024) } ?: ""
            FetchOutcome.Success(html, UrlSanitizer.stripTrackingParams(response.request.url.toString()))
        }
    }

    private fun blockedMessage(code: Int): String = if (Locale.getDefault().language == "de") {
        "Zugriff verweigert (HTTP $code)"
    } else {
        "Access denied (HTTP $code)"
    }

    suspend fun extractFromUrl(url: String, stripTracking: Boolean = true): ExtractedArticle = withContext(Dispatchers.IO) {
        extractFromUrlWithRaw(url, stripTracking).first
    }

    suspend fun extractFromUrlWithRaw(url: String, stripTracking: Boolean = true): Pair<ExtractedArticle, String> = withContext(Dispatchers.IO) {
        val targetUrl = if (stripTracking) UrlSanitizer.stripTrackingParams(url) else url.trim()
        require(EInkImageOptimizer.isSafeHttpsExternalUrl(targetUrl)) {
            if (Locale.getDefault().language == "de") {
                "Ungültige oder interne URL blockiert."
            } else {
                "Invalid or internal URL blocked."
            }
        }

        var usedCookies = false
        var fetchResult = executeFetch(getStatelessHttpClient(), targetUrl)
        if (fetchResult is FetchOutcome.Unauthorized) {
            if (hasCookiesForUrl(targetUrl)) {
                usedCookies = true
                fetchResult = executeFetch(getCookieHttpClient(), targetUrl)
            }
            if (fetchResult is FetchOutcome.Unauthorized) {
                throw PaywallOrLoginRequiredException(targetUrl, blockedMessage(fetchResult.code))
            }
        }

        var success = fetchResult as FetchOutcome.Success
        var doc = Jsoup.parse(success.html, success.effectiveUrl)
        var article = extractFromDocument(doc, success.effectiveUrl)
        if (article.wordCount < 100) {
            article = tryFollowContentIframe(doc, success.effectiveUrl, article, useCookies = usedCookies)
        }

        if (isPaywallDetected(doc, article.contentHtml, article.wordCount)) {
            val cookieAvailable = hasCookiesForUrl(targetUrl) || hasCookiesForUrl(success.effectiveUrl)
            if (!usedCookies && cookieAvailable) {
                usedCookies = true
                val retryResult = executeFetch(getCookieHttpClient(), targetUrl)
                if (retryResult is FetchOutcome.Unauthorized) {
                    throw PaywallOrLoginRequiredException(targetUrl, blockedMessage(retryResult.code))
                }
                success = retryResult as FetchOutcome.Success
                doc = Jsoup.parse(success.html, success.effectiveUrl)
                article = extractFromDocument(doc, success.effectiveUrl)
                if (article.wordCount < 100) {
                    article = tryFollowContentIframe(doc, success.effectiveUrl, article, useCookies = true)
                }
            }
            if (isPaywallDetected(doc, article.contentHtml, article.wordCount)) {
                throw PaywallOrLoginRequiredException(targetUrl, article.title)
            }
        }

        Pair(article, success.html)
    }

    private suspend fun tryFollowContentIframe(
        doc: Document,
        baseUrl: String,
        currentArticle: ExtractedArticle,
        useCookies: Boolean = false
    ): ExtractedArticle = withContext(Dispatchers.IO) {
        val nonContentHosts = listOf(
            "youtube.com", "youtu.be", "vimeo.com", "twitter.com", "x.com",
            "facebook.com", "instagram.com", "google.com", "recaptcha",
            "doubleclick", "googletagmanager", "disqus.com", "stripe.com",
            "paypal.com", "spotify.com", "soundcloud.com"
        )
        val iframeCandidates = doc.select("iframe[src]").map {
            UrlSanitizer.stripTrackingParams(it.absUrl("src").ifBlank { it.attr("src") }.trim())
        }
        val contentIframeUrl = iframeCandidates.firstOrNull { iframeUrl ->
            EInkImageOptimizer.isSafeHttpsExternalUrl(iframeUrl) &&
            nonContentHosts.none { iframeUrl.contains(it, ignoreCase = true) }
        } ?: return@withContext currentArticle

        try {
            val client = if (useCookies && hasCookiesForUrl(contentIframeUrl)) getCookieHttpClient() else getStatelessHttpClient()
            val req = Request.Builder()
                .url(contentIframeUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
                .build()

            val (iframeHtml, iframeEffectiveUrl) = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext currentArticle

                val html = resp.body?.let { EInkImageOptimizer.readBoundedString(it, 10L * 1024 * 1024) } ?: ""
                html to UrlSanitizer.stripTrackingParams(resp.request.url.toString())
            }
            val iframeDoc = Jsoup.parse(iframeHtml, iframeEffectiveUrl)
            val iframeArticle = extractFromDocument(iframeDoc, iframeEffectiveUrl)

            if (iframeArticle.wordCount > currentArticle.wordCount) {
                val mergedTitle = if (!isGenericTitle(currentArticle.title)) currentArticle.title else iframeArticle.title
                return@withContext ExtractedArticle(
                    title = mergedTitle,
                    subtitle = currentArticle.subtitle ?: iframeArticle.subtitle,
                    author = currentArticle.author ?: iframeArticle.author,
                    siteName = currentArticle.siteName ?: iframeArticle.siteName,
                    leadImageUrl = currentArticle.leadImageUrl ?: iframeArticle.leadImageUrl,
                    contentHtml = iframeArticle.contentHtml,
                    imageUrls = (currentArticle.imageUrls + iframeArticle.imageUrls).distinct(),
                    sourceUrl = baseUrl,
                    wordCount = iframeArticle.wordCount,
                    readingTimeMinutes = iframeArticle.readingTimeMinutes,
                    publishedDate = currentArticle.publishedDate ?: iframeArticle.publishedDate,
                    language = currentArticle.language ?: iframeArticle.language
                )
            }
        } catch (e: Exception) {
            SafeLogger.w(tag, "Failed to follow content iframe: ${SafeLogger.redactUrl(contentIframeUrl)}", e)
        }

        currentArticle
    }

    fun isPaywallDetected(doc: Document, contentHtml: String, wordCount: Int): Boolean {
        if (wordCount > 450) return false

        val plainText = Jsoup.parse(contentHtml).text().lowercase()
        val paywallKeywords = listOf(
            "subscribe to continue reading",
            "subscribe to read",
            "to read the full story",
            "create a free account to continue",
            "sign in to continue reading",
            "sign in to read",
            "log in to continue",
            "this article is for subscribers only",
            "this story is for subscribers",
            "already a subscriber? sign in",
            "exklusiv für abonnenten",
            "weiterlesen mit plus",
            "nur für abonnenten",
            "paywall",
            "join now to read",
            "become a member to read",
            "read the rest of this story"
        )
        val hasKeyword = paywallKeywords.any { plainText.contains(it) }

        val hasPaywallElements = doc.select(
            "[class*=paywall], [id*=paywall], [class*=gate-content], [id*=gate-content], [class*=metered-paywall], .subscriber-only, [data-component*=paywall]"
        ).isNotEmpty()

        return hasKeyword || hasPaywallElements
    }

    suspend fun extractFromHtml(html: String, baseUrl: String): ExtractedArticle = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(html, baseUrl)
        var article = extractFromDocument(doc, baseUrl)
        if (article.wordCount < 100 && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            article = tryFollowContentIframe(doc, baseUrl, article)
        }
        article
    }

    private fun extractFromDocument(doc: Document, sourceUrl: String): ExtractedArticle {
        // 0. Infer canonical sourceUrl and document language
        val rawLanguage = doc.selectFirst("html")?.attr("lang")?.ifBlank {
            doc.selectFirst("html")?.attr("xml:lang").orEmpty()
        }?.ifBlank {
            doc.selectFirst("meta[property=og:locale]")?.attr("content").orEmpty()
        }?.ifBlank {
            doc.selectFirst("meta[http-equiv=content-language]")?.attr("content").orEmpty()
        }
        val detectedLanguage = rawLanguage
            ?.trim()
            ?.replace('_', '-')
            ?.split('-', ',')
            ?.firstOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }

        val canonicalUrl = doc.select("link[rel=canonical]").attr("href").ifBlank {
            doc.select("meta[property=og:url]").attr("content").ifBlank {
                doc.select("meta[name=twitter:url]").attr("content")
            }
        }.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val rawEffectiveSourceUrl = if (canonicalUrl != null && (sourceUrl.isBlank() || sourceUrl.contains("local"))) {
            canonicalUrl
        } else {
            sourceUrl
        }
        val effectiveSourceUrl = UrlSanitizer.stripTrackingParams(rawEffectiveSourceUrl)

        // 1. Title Extraction
        var rawTitle = doc.select("meta[property=og:title]").attr("content").ifBlank {
            doc.select("meta[name=twitter:title]").attr("content").ifBlank {
                val docTitle = doc.title().trim().takeIf { !isGenericTitle(it) }
                docTitle ?: run {
                    doc.select("h1").map { it.text().trim() }.firstOrNull { it.length in 5..120 && !isGenericTitle(it) }
                        ?: doc.select("[class*=headline], [class*=title]").map { it.text().trim() }.firstOrNull { it.length in 5..120 && !isGenericTitle(it) }
                        ?: ""
                }
            }
        }.trim()

        rawTitle = cleanTitle(rawTitle)

        val untitledFallback = if (java.util.Locale.getDefault().language == "de") "Unbenannter Artikel" else "Untitled Article"
        // If title is generic, starts with a kicker, or is excessively long, search for better candidates
        if (isGenericTitle(rawTitle) || hasKickerPrefix(rawTitle) || rawTitle.length > 80) {
            val newsletterSlug = extractTitleFromNewsletterWebLink(doc)
            val kickerFromDoc = extractKickerFromDocument(doc)
            val h1Candidate = doc.select("h1").map { cleanTitle(it.text()) }.firstOrNull { it.length in 5..100 && !isGenericTitle(it) }
            val slugCandidate = extractTitleFromUrlSlug(effectiveSourceUrl)
            val h2Candidate = doc.select("h2, [class*=headline]").map { cleanTitle(it.text()) }.firstOrNull { it.length in 5..100 && !isGenericTitle(it) }
            rawTitle = when {
                newsletterSlug != null -> newsletterSlug
                kickerFromDoc != null -> kickerFromDoc
                h1Candidate != null -> h1Candidate
                slugCandidate != null -> slugCandidate
                h2Candidate != null -> h2Candidate
                else -> rawTitle.ifBlank { untitledFallback }
            }
        }
        val title = sanitizeDocumentTitle(cleanTitle(rawTitle)).ifBlank { untitledFallback }

        // 3. Site Name
        val siteName = doc.select("meta[property=og:site_name]").attr("content").ifBlank {
            try {
                URL(effectiveSourceUrl).host.removePrefix("www.")
            } catch (e: Exception) {
                null
            }
        }

        // 4. Lead Image (Cover Image)
        var leadImageUrl = doc.select("meta[property=og:image]").attr("content").ifBlank {
            doc.select("meta[name=twitter:image]").attr("content")
        }.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }

        if (leadImageUrl == null) {
            val candidateImg = doc.select("article figure img, main figure img, article img, main img, figure img").firstOrNull()
            if (candidateImg != null) {
                val best = extractBestImageUrl(candidateImg)
                if (best != null && isArticleImage(best, candidateImg)) {
                    leadImageUrl = best
                }
            }
        }

        // 4b. Published Date Extraction (before script/style elements are removed)
        val rawDate = sequenceOf(
            doc.select("meta[property=article:published_time]").attr("content"),
            doc.select("meta[name=article:published_time]").attr("content"),
            doc.select("meta[property=og:published_time]").attr("content"),
            doc.select("meta[name=publication_date]").attr("content"),
            doc.select("meta[name=date]").attr("content"),
            doc.select("meta[name=pubdate]").attr("content"),
            doc.select("meta[name=sailthru.date]").attr("content"),
            doc.select("meta[name=parsely-pub-date]").attr("content"),
            doc.select("meta[name=DC.date.issued]").attr("content"),
            doc.select("time[datetime]").firstOrNull()?.attr("datetime"),
            doc.select("time").firstOrNull()?.text()
        ).firstOrNull { !it.isNullOrBlank() }

        var publishedDate = com.crosssend.app.util.DateUtils.parseDate(rawDate)

        if (publishedDate == null) {
            for (script in doc.select("script[type=application/ld+json]")) {
                val json = script.data()
                val match = "\"datePublished\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)
                    ?: "\"dateCreated\"\\s*:\\s*\"([^\"]+)\"".toRegex().find(json)
                if (match != null) {
                    publishedDate = com.crosssend.app.util.DateUtils.parseDate(match.groupValues[1])
                    if (publishedDate != null) break
                }
            }
        }

        // 5. Remove clutter elements (popovers, dialogs, drawers, ads, social bars, asides)
        val removeSelectors = listOf(
            "script", "style", "noscript", "svg", "nav", "footer",
            "aside:not([class*=quote]):not([class*=pullquote]):not([class*=callout])",
            ".ad", ".ads", ".advertisement", ".cookie-banner", ".share-buttons",
            ".comments", "#comments", ".social", ".sidebar", ".related-posts",
            "[data-native-ad-id]", "form", "button",
            "[role=dialog]", "[aria-modal=true]", "[aria-hidden=true]",
            "[id^=popover]", "[class*=popover]", "[class*=drawer]", "[id*=drawer]",
            "[style*='display:none']", "[style*='display: none']", "[style*='visibility: hidden']",
            ".share-bar", ".social-share", "[class*='share-']", "[class*='follow-']",
            "[class*='byline-lead']", "[class*='author-popover']",
            "[class*='post-footer']", "[class*='article-footer']",
            "[class*='comments-section']", "[class*='comment-list']",
            "[class*='subscribe-widget']", "[class*='newsletter-signup']",
            "[class*='newsletter-widget']", "[class*='subscription-widget']",
            "[class*='email-signup']", "[class*='related-stories']",
            "[class*='related-articles']", "[class*='more-stories']", "[class*='more-from']"
        )
        for (selector in removeSelectors) {
            doc.select(selector).remove()
        }
        doc.select("header").filter { it.select("h1, h2, h3").isEmpty() }.forEach { it.remove() }

        // 6. Subtitle / Deck Extraction (Priority: on-page DOM deck/lead under H1, then selectors, then meta)
        var subtitle: String? = null
        val h1El = doc.select("h1").firstOrNull()
        if (h1El != null) {
            var sib: Element? = h1El.nextElementSibling()
            while (sib != null && sib.tagName() in listOf("figure", "picture", "time", "span")) {
                sib = sib.nextElementSibling()
            }
            if (sib != null && sib.tagName().equals("p", ignoreCase = true)) {
                val sibText = sib.text().trim()
                if (sibText.length in 10..400 && !sibText.startsWith("By ", ignoreCase = true)) {
                    subtitle = sibText
                }
            } else {
                // If h1 is in header, check header's sibling or parent
                var headerSibling = h1El.parent()?.nextElementSibling()
                while (headerSibling != null && headerSibling.tagName() in listOf("figure", "picture", "time", "span")) {
                    headerSibling = headerSibling.nextElementSibling()
                }
                if (headerSibling != null && headerSibling.tagName().equals("p", ignoreCase = true)) {
                    val sibText = headerSibling.text().trim()
                    if (sibText.length in 10..400 && !sibText.startsWith("By ", ignoreCase = true)) {
                        subtitle = sibText
                    }
                } else {
                    val parentP = h1El.parent()?.select("p")?.firstOrNull { p ->
                        val t = p.text().trim()
                        t.length in 10..400 && !t.startsWith("By ", ignoreCase = true) && !t.contains("follow", ignoreCase = true)
                    }
                    if (parentP != null) {
                        subtitle = parentP.text().trim()
                    }
                }
            }
        }

        if (subtitle.isNullOrBlank()) {
            val candidateEl = doc.select(".deck, .subtitle, [class*=dek], [class*=subtitle], [class*=lead-text], [class*=standfirst], [class*=subheadline], [class*=summary]").firstOrNull()
            if (candidateEl != null) {
                val text = candidateEl.text().trim()
                if (text.length in 10..400) {
                    subtitle = text
                }
            }
        }

        if (subtitle.isNullOrBlank()) {
            val metaDesc = doc.select("meta[property=og:description]").attr("content").ifBlank {
                doc.select("meta[name=description]").attr("content")
            }.trim()
            if (metaDesc.isNotBlank() && metaDesc.length in 10..400) {
                subtitle = metaDesc
            }
        }

        // 7. Author Extraction (popovers/asides already stripped)
        var author = (doc.select("meta[property=article:author]").attr("content").ifBlank {
            doc.select("meta[name=author]").attr("content").ifBlank {
                val authorEl = doc.select(
                    "[rel=author], [itemprop=author], .author, .byline, [class*=author], [class*=byline], [id*=author], [id*=byline]"
                ).firstOrNull()?.clone()
                authorEl?.select("button, svg, aside, style, script, [role=dialog], [aria-hidden=true], [class*=popover]")?.remove()
                authorEl?.text()?.replace("See All by", "", ignoreCase = true)?.replace("By ", "", ignoreCase = true)?.trim()
            }
        })?.trim()?.ifBlank { null }

        // Fallback for newsletter / email HTML author header heuristic (e.g. "Anette Dowideit\nChefredakteurin")
        if (author.isNullOrBlank()) {
            val candidateBlocks = mutableListOf<String>()
            for (el in doc.select("p, div, td, header").take(8)) {
                val brSplit = el.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
                for (part in brSplit) {
                    val t = Jsoup.parseBodyFragment(part).text().trim()
                    if (t.isNotBlank() && !candidateBlocks.contains(t)) candidateBlocks.add(t)
                }
            }
            val editorialRoles = listOf(
                "chefredakteur", "chefredakteurin", "chefredaktion", "redakteur", "redakteurin",
                "redaktion", "autor", "autorin", "editor", "editor-in-chief", "reporter",
                "journalist", "journalistin", "korrespondent", "korrespondentin", "herausgeber",
                "herausgeberin", "publisher", "kolumnist", "kolumnistin", "columnist", "gastautor"
            )
            for (i in 0 until minOf(candidateBlocks.size - 1, 6)) {
                val candidateAuthor = candidateBlocks[i]
                val candidateRole = candidateBlocks[i + 1].lowercase()
                val isAuthorWords = candidateAuthor.split("\\s+".toRegex()).size in 2..4 &&
                        !candidateAuthor.contains(":") && !candidateAuthor.contains("http") && !candidateAuthor.startsWith("[")
                val isRole = editorialRoles.any { candidateRole == it || candidateRole.startsWith("$it ") || candidateRole.startsWith("$it,") }
                if (isAuthorWords && isRole) {
                    author = candidateAuthor
                    break
                }
            }
        }

        // 6. Find main article container
        val candidateSelectors = listOf(
            "article",
            "[role=main]",
            ".article-content",
            ".post-content",
            ".entry-content",
            ".story-content",
            "#main-content",
            "main"
        )

        var mainElement: Element? = null
        for (selector in candidateSelectors) {
            val el = doc.select(selector).first()
            if (el != null && el.text().length > 300) {
                mainElement = el
                break
            }
        }

        if (mainElement == null) {
            var maxScore = 0
            for (el in doc.select("div, section")) {
                val pCount = el.select("p").size
                if (pCount < 2) continue // Do not pick containers that have fewer than 2 paragraphs
                val textLength = el.text().length
                val score = pCount * 100 + (textLength / 50)
                if (score > maxScore) {
                    maxScore = score
                    mainElement = el
                }
            }
        }

        // If body has more paragraphs than mainElement, prefer body to avoid dropping newsletter paragraphs
        val bodyPCount = doc.body().select("p").size
        val mainPCount = mainElement?.select("p")?.size ?: 0
        val target = if (mainElement != null && mainPCount >= (bodyPCount * 0.6).toInt()) {
            mainElement
        } else {
            doc.body()
        }

        // Ensure standalone text inside div/td/section is wrapped in <p> so it is not dropped
        for (container in target.select("div, td, section")) {
            if (container.select("p, h1, h2, h3, h4, h5, h6, ul, ol, blockquote, figure, img").isEmpty()) {
                val text = container.text().trim()
                if (text.isNotBlank() && text.length > 20) {
                    val doubleBrRegex = Regex("<br\\s*/?>\\s*<br\\s*/?>", RegexOption.IGNORE_CASE)
                    if (doubleBrRegex.containsMatchIn(container.html())) {
                        val parts = container.html().split(doubleBrRegex)
                        val newHtml = parts.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n") { "<p>$it</p>" }
                        container.html(newHtml)
                    } else {
                        container.html("<p>${sanitizeInlineFormatting(container)}</p>")
                    }
                }
            }
        }

        // 7. Extract block elements in reading order with rich inline formatting
        val inlineImageUrls = mutableListOf<String>()
        val seenCanonicalImageUrls = mutableSetOf<String>()
        leadImageUrl?.let { seenCanonicalImageUrls.add(canonicalImageUrl(it)) }

        val extractedBlocks = mutableListOf<String>()
        val processedElements = mutableSetOf<Element>()
        var narrativeParagraphsSeen = 0

        val blockElements = target.select("h1, h2, h3, h4, h5, h6, p, blockquote, aside, [class*='pullquote'], [class*='callout'], ul, ol, figure, img, pre")

        for (el in blockElements) {
            if (processedElements.contains(el)) continue

            when (el.tagName().lowercase()) {
                "figure" -> {
                    processedElements.addAll(el.getAllElements())
                    val img = el.select("img").first()
                    val caption = el.select("figcaption").text().trim()

                    if (img != null) {
                        val bestUrl = extractBestImageUrl(img)
                        if (bestUrl != null && isArticleImage(bestUrl, img)) {
                            val canUrl = canonicalImageUrl(bestUrl)
                            if (!seenCanonicalImageUrls.contains(canUrl)) {
                                seenCanonicalImageUrls.add(canUrl)
                                inlineImageUrls.add(bestUrl)
                                val safeUrl = escapeHtml(bestUrl)
                                val defaultAlt = if (java.util.Locale.getDefault().language == "de") "Abbildung" else "Figure"
                                val alt = escapeHtml(img.attr("alt").ifBlank { defaultAlt })
                                val capHtml = if (caption.isNotBlank()) "\n<p><em>${escapeHtml(caption)}</em></p>" else ""
                                extractedBlocks.add("<p><img src=\"$safeUrl\" alt=\"$alt\" /></p>$capHtml")
                            }
                        }
                    }
                }
                "img" -> {
                    processedElements.add(el)
                    val bestUrl = extractBestImageUrl(el)
                    if (bestUrl != null && isArticleImage(bestUrl, el)) {
                        val canUrl = canonicalImageUrl(bestUrl)
                        if (!seenCanonicalImageUrls.contains(canUrl)) {
                            seenCanonicalImageUrls.add(canUrl)
                            inlineImageUrls.add(bestUrl)
                            val safeUrl = escapeHtml(bestUrl)
                            val defaultAlt = if (java.util.Locale.getDefault().language == "de") "Abbildung" else "Figure"
                            val alt = escapeHtml(el.attr("alt").ifBlank { defaultAlt })
                            extractedBlocks.add("<p><img src=\"$safeUrl\" alt=\"$alt\" /></p>")
                        }
                    }
                }
                "p" -> {
                    processedElements.add(el)
                    val isPullquote = el.classNames().any { it.contains("pullquote", ignoreCase = true) || it.contains("callout", ignoreCase = true) }
                    if (isPullquote) {
                        val sanitizedHtml = sanitizeInlineFormatting(el)
                        if (sanitizedHtml.isNotBlank()) {
                            extractedBlocks.add("<blockquote class=\"pullquote\">$sanitizedHtml</blockquote>")
                        }
                        continue
                    }

                    val innerImg = el.select("img").first()
                    if (innerImg != null) {
                        val bestUrl = extractBestImageUrl(innerImg)
                        if (bestUrl != null && isArticleImage(bestUrl, innerImg)) {
                            val canUrl = canonicalImageUrl(bestUrl)
                            if (!seenCanonicalImageUrls.contains(canUrl)) {
                                seenCanonicalImageUrls.add(canUrl)
                                inlineImageUrls.add(bestUrl)
                                val safeUrl = escapeHtml(bestUrl)
                                val defaultAlt = if (java.util.Locale.getDefault().language == "de") "Abbildung" else "Figure"
                                val alt = escapeHtml(innerImg.attr("alt").ifBlank { defaultAlt })
                                extractedBlocks.add("<p><img src=\"$safeUrl\" alt=\"$alt\" /></p>")
                            }
                        }
                    }

                    val elPlainText = el.text().trim()
                    val sanitizedHtml = sanitizeInlineFormatting(el)
                    if (sanitizedHtml.isBlank()) continue

                    val isDuplicateSubtitle = subtitle != null && (
                        elPlainText.equals(subtitle, ignoreCase = true) ||
                        sanitizedHtml.equals(subtitle, ignoreCase = true)
                    )
                    if (isDuplicateSubtitle) continue

                    // Before the first 2 substantive narrative paragraphs, filter leading metadata clutter
                    if (narrativeParagraphsSeen < 2) {
                        if (isByline(elPlainText, author)) continue
                        if (isPhotoCredit(elPlainText)) continue
                        if (isAuthorBio(elPlainText, author)) continue
                    }

                    if (elPlainText.split("\\s+".toRegex()).size >= 15) {
                        narrativeParagraphsSeen++
                    }

                    extractedBlocks.add("<p>$sanitizedHtml</p>")
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    processedElements.add(el)
                    val tag = el.tagName().lowercase()
                    val elText = el.text().trim()
                    // Don't repeat title if it's an h1 matching the article title (EpubBuilder renders title)
                    val isDuplicateTitle = tag == "h1" && (
                        elText.equals(title, ignoreCase = true) ||
                        (elText.length > 5 && (title.startsWith(elText, ignoreCase = true) || elText.startsWith(title, ignoreCase = true)))
                    )
                    if (!isDuplicateTitle) {
                        // Before the first 2 substantive narrative paragraphs, filter leading duplicate subtitle or bylines
                        if (narrativeParagraphsSeen < 2) {
                            if (!subtitle.isNullOrBlank() && elText.equals(subtitle, ignoreCase = true)) continue
                            if (isByline(elText, author)) continue
                        }

                        if (elText.length > 120 || elText.split("\\s+".toRegex()).size > 15 || elText.contains("\n")) {
                            val sentences = elText.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotBlank() }
                            if (sentences.isNotEmpty()) {
                                val headText = sentences.first()
                                val safeHeadText = if (headText.length > 100) headText.take(75).substringBeforeLast(" ", headText.take(75)) else headText
                                extractedBlocks.add("<$tag>${escapeHtml(safeHeadText)}</$tag>")
                                val remainingSentences = sentences.drop(1)
                                var pGroup = StringBuilder()
                                var sCount = 0
                                for (s in remainingSentences) {
                                    if (pGroup.isNotEmpty()) pGroup.append(" ")
                                    pGroup.append(s)
                                    sCount++
                                    if (sCount >= 3 || pGroup.length > 300) {
                                        extractedBlocks.add("<p>${escapeHtml(pGroup.toString())}</p>")
                                        pGroup = StringBuilder()
                                        sCount = 0
                                    }
                                }
                                if (pGroup.isNotEmpty()) {
                                    extractedBlocks.add("<p>${escapeHtml(pGroup.toString())}</p>")
                                }
                            }
                        } else {
                            val sanitizedHtml = sanitizeInlineFormatting(el)
                            if (sanitizedHtml.isNotBlank()) {
                                extractedBlocks.add("<$tag>$sanitizedHtml</$tag>")
                            }
                        }
                    }
                }
                "blockquote", "aside" -> {
                    processedElements.addAll(el.getAllElements())
                    val isPullquote = el.tagName().equals("aside", ignoreCase = true) ||
                        el.classNames().any { it.contains("pullquote", ignoreCase = true) || it.contains("callout", ignoreCase = true) }
                    val sanitizedHtml = sanitizeInlineFormatting(el)
                    if (sanitizedHtml.isNotBlank()) {
                        val cssClass = if (isPullquote) " class=\"pullquote\"" else ""
                        extractedBlocks.add("<blockquote$cssClass>$sanitizedHtml</blockquote>")
                    }
                }
                "pre" -> {
                    processedElements.addAll(el.getAllElements())
                    val codeText = el.text()
                    if (codeText.isNotBlank()) {
                        extractedBlocks.add("<pre><code>${escapeHtml(codeText)}</code></pre>")
                    }
                }
                "ul", "ol" -> {
                    processedElements.addAll(el.getAllElements())
                    val tag = el.tagName().lowercase()
                    val lis = el.select("li").filter { it.text().isNotBlank() }
                    if (lis.isNotEmpty()) {
                        val sb = StringBuilder()
                        sb.append("<$tag>\n")
                        for (li in lis) {
                            val liHtml = sanitizeInlineFormatting(li)
                            if (liHtml.isNotBlank()) {
                                sb.append("  <li>$liHtml</li>\n")
                            }
                        }
                        sb.append("</$tag>")
                        extractedBlocks.add(sb.toString())
                    }
                }
                else -> {
                    if (el.classNames().any { it.contains("pullquote", ignoreCase = true) || it.contains("callout", ignoreCase = true) }) {
                        processedElements.addAll(el.getAllElements())
                        val sanitizedHtml = sanitizeInlineFormatting(el)
                        if (sanitizedHtml.isNotBlank()) {
                            extractedBlocks.add("<blockquote class=\"pullquote\">$sanitizedHtml</blockquote>")
                        }
                    }
                }
            }
        }

        // Pop trailing clutter from the end (share buttons, related links, newsletter signup blurbs)
        while (extractedBlocks.isNotEmpty() && isTrailingClutter(extractedBlocks.last(), author)) {
            extractedBlocks.removeAt(extractedBlocks.size - 1)
        }

        val cleanHtml = if (extractedBlocks.isNotEmpty()) extractedBlocks.joinToString("\n") else "<p>${escapeHtml(target.text())}</p>"

        // Calculate word count and estimated reading time
        val plainText = Jsoup.parse(cleanHtml).text()
        val wordCount = plainText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        val readingTimeMinutes = maxOf(1, Math.round(wordCount / 210.0).toInt())

        return ExtractedArticle(
            title = title,
            subtitle = subtitle,
            author = author,
            siteName = siteName,
            leadImageUrl = leadImageUrl,
            contentHtml = cleanHtml,
            imageUrls = inlineImageUrls.distinct(),
            sourceUrl = effectiveSourceUrl,
            wordCount = wordCount,
            readingTimeMinutes = readingTimeMinutes,
            publishedDate = publishedDate,
            language = detectedLanguage
        )
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Preserves bold, italic, code, links and strike-through while stripping clutter.
     */
    private fun sanitizeInlineFormatting(element: Element): String {
        val allowedTags = setOf("strong", "b", "em", "i", "code", "a", "del", "s", "mark", "sub", "sup")
        val clone = element.clone()
        clone.select("img, script, style, noscript, iframe, object, embed, form, input, button").remove()

        for (child in clone.allElements) {
            if (child == clone) continue
            val tagLower = child.tagName().lowercase(Locale.ROOT)
            val attrsToRemove = mutableListOf<String>()
            var sanitizedHref: String? = null
            for (attr in child.attributes()) {
                val keyLower = attr.key.lowercase(Locale.ROOT)
                if (tagLower == "a" && keyLower == "href" && EInkImageOptimizer.isSafeHtmlUri(attr.value, allowRelative = true)) {
                    sanitizedHref = UrlSanitizer.stripTrackingParams(attr.value.trim())
                } else {
                    // Strict attribute allowlist: strip style, class, id, on*, ping, data-*, etc.
                    attrsToRemove.add(attr.key)
                }
            }
            for (attrKey in attrsToRemove) {
                child.removeAttr(attrKey)
            }
            if (sanitizedHref != null) {
                child.attr("href", sanitizedHref)
            }
            if (tagLower !in allowedTags) {
                child.unwrap()
            }
        }
        return clone.html().trim()
    }

    private fun extractBestImageUrl(img: Element): String? {
        val srcSet = img.attr("srcSet").ifBlank { img.attr("srcset") }
        if (srcSet.isNotBlank()) {
            val entryRegex = Regex("""(https?://[^\s]+)(?:\s+(\d+)[wx])?""")
            val candidates = entryRegex.findAll(srcSet).map { match ->
                val rawUrl = match.groupValues[1].removeSuffix(",").trim()
                val width = match.groupValues[2].toIntOrNull() ?: 0
                rawUrl to width
            }.toList()

            for ((url, width) in candidates) {
                if (width in 500..1200 && (url.startsWith("http://") || url.startsWith("https://"))) {
                    return url
                }
            }
            if (candidates.isNotEmpty()) {
                val fallbackUrl = candidates.firstOrNull { it.first.startsWith("http://") || it.first.startsWith("https://") }?.first
                    ?: candidates.last().first
                if (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://")) {
                    return fallbackUrl
                }
            }
        }

        val src = img.absUrl("src").ifBlank { img.attr("src") }
        if (src.isNotBlank() && !src.startsWith("data:") && (src.startsWith("http://") || src.startsWith("https://"))) {
            return src
        }

        val dataSrc = img.absUrl("data-src").ifBlank { img.attr("data-src") }
        if (dataSrc.isNotBlank() && (dataSrc.startsWith("http://") || dataSrc.startsWith("https://"))) {
            return dataSrc
        }

        return null
    }

    fun canonicalImageUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.substringBefore('?').substringBefore('#').trim()
    }

    private fun isArticleImage(url: String, img: Element): Boolean {
        val lower = url.lowercase()
        if (lower.contains("google-analytics") || lower.contains("author_profile") ||
            lower.contains("avatar") || lower.contains("favicon") || lower.contains("1x1") ||
            lower.contains("icon") || lower.contains("logo") || lower.contains("w_140,h_140")) {
            return false
        }
        val width = img.attr("width").toIntOrNull() ?: 0
        val height = img.attr("height").toIntOrNull() ?: 0
        if ((width in 1..150 && height in 1..150) || (width in 1..50) || (height in 1..50)) {
            return false
        }
        return true
    }

    fun isByline(text: String, author: String?): Boolean {
        val t = text.trim()
        if (t.length > 120) return false
        if (t.contains("<!-- -->") && (Regex("^(?:by|von)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) || (author != null && t.contains(author, ignoreCase = true)))) {
            return true
        }
        val byPrefixRegex = Regex("^(?:by|von|written by|author:?|verfasst von)\\s+", RegexOption.IGNORE_CASE)
        if (byPrefixRegex.containsMatchIn(t)) {
            // Guard against narrative phrases like "By the time...", "By then..."
            if (Regex("^(?:by|von)\\s+(?:the|then|this|that|now|contrast|comparison|all|far|way|chance|accident|definition|default)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                return false
            }
            if (author != null && t.contains(author, ignoreCase = true)) return true
            val dateMonthRegex = Regex("\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember|\\d{4})\\b", RegexOption.IGNORE_CASE)
            if (dateMonthRegex.containsMatchIn(t)) return true
            if (t.split("\\s+".toRegex()).size <= 6) return true
        }
        if (author != null && t.startsWith(author, ignoreCase = true)) {
            val remainder = t.substring(author.length).trim()
            if (remainder.isBlank() || Regex("^(?:[·•\\s-]*)(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|\\d{1,2}[\\./])", RegexOption.IGNORE_CASE).containsMatchIn(remainder)) {
                return true
            }
        }
        return false
    }

    fun isPhotoCredit(text: String): Boolean {
        val t = text.trim()
        if (t.length > 150) return false
        val startsWithCredit = Regex("^(?:image|photo|bild|foto|credit|quelle|source|illustration|titelbild|grafik)\\s*(?::|\\||–|-)\\s*", RegexOption.IGNORE_CASE)
        if (startsWithCredit.containsMatchIn(t)) return true
        val endsWithCredit = Regex("(?:\\||–|-|\\b)(?:photo|foto|bild|image|credit|quelle)\\s*:\\s*[^<]+$", RegexOption.IGNORE_CASE)
        if (endsWithCredit.containsMatchIn(t) && t.split("\\s+".toRegex()).size <= 15) return true
        val photographyBy = Regex("^(?:photography|foto|bild|aufnahme)\\s+(?:by|von)\\s+", RegexOption.IGNORE_CASE)
        if (photographyBy.containsMatchIn(t) && t.length < 80) return true
        return false
    }

    fun isAuthorBio(text: String, author: String?): Boolean {
        if (author.isNullOrBlank() || text.length > 350) return false
        val t = text.trim()
        val parts = author.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val firstName = parts.firstOrNull() ?: author
        val lastName = parts.lastOrNull() ?: author
        if (!t.startsWith(author, ignoreCase = true) && !t.startsWith(firstName, ignoreCase = true) && !t.startsWith(lastName, ignoreCase = true)) {
            return false
        }
        val rolePattern = Regex(
            "\\b(?:is (?:a|an|the)|senior|reporter|editor|writer|journalist|co-host|host|columnist|freelance|contributor|covers|covering|previously|ist (?:ein|eine|redakteur|reporter|journalist|autor))\\b",
            RegexOption.IGNORE_CASE
        )
        return rolePattern.containsMatchIn(t)
    }

    fun isTrailingClutter(html: String, author: String?): Boolean {
        val rawText = Jsoup.parseBodyFragment(html).text().trim()
        val tag = Regex("^<([a-z0-9]+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.lowercase() ?: ""

        if (rawText.isBlank() && !html.contains("<img", ignoreCase = true)) {
            return true
        }
        if (tag in listOf("p", "div")) {
            if (rawText.equals("Share", ignoreCase = true) || rawText.equals("Teilen", ignoreCase = true) || rawText.equals("Diesen Artikel teilen", ignoreCase = true) ||
                (Regex("^(?:share|teilen|tweet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawText) && rawText.length < 40)) {
                return true
            }
            if (Regex("^(?:if you liked this|enjoyed this|free to subscribe|subscribe to|sign up for|abonnieren sie)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawText) && rawText.length < 350) {
                return true
            }
            if (Regex("(?:share it in the comments|leave a comment|post a comment|in den kommentaren)", RegexOption.IGNORE_CASE).containsMatchIn(rawText) && rawText.length < 120) {
                return true
            }
        }
        if (tag in listOf("ul", "ol")) {
            val listDoc = Jsoup.parseBodyFragment(html)
            val lis = listDoc.select("li")
            if (lis.size == 1) {
                val liText = lis.first()?.text()?.trim() ?: ""
                if (author != null && liText.equals(author, ignoreCase = true)) return true
                if (liText.equals("Share", ignoreCase = true) || liText.equals("Teilen", ignoreCase = true)) return true
            }
            val allLinks = lis.all { it.select("a").isNotEmpty() }
            if (allLinks && lis.size >= 2) return true
        }
        if (tag in listOf("h1", "h2", "h3", "h4", "h5", "h6")) {
            val lower = rawText.lowercase()
            val keywords = listOf(
                "most popular", "related", "trending", "read next", "more from",
                "weiterlesen", "continue without a seat", "breakfast is included",
                "comments", "kommentare", "discussion"
            )
            if (keywords.any { lower.contains(it) }) return true
        }
        return false
    }

    companion object {
        private val sameOriginCookieInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request()
            val reqBuilder = req.newBuilder().removeHeader("Cookie")
            try {
                val storedCookies = CookieManager.getInstance().getCookie(req.url.toString())
                if (!storedCookies.isNullOrBlank()) {
                    reqBuilder.header("Cookie", storedCookies)
                }
            } catch (_: Throwable) {}
            chain.proceed(reqBuilder.build())
        }

        val GENERIC_TITLES = setOf(
            "web", "webversion", "web-version", "web version", "web-artikel", "web artikel",
            "online", "online-version", "onlineversion", "online version", "im web", "im web lesen",
            "im browser", "im browser lesen", "browser", "browser-ansicht", "browser ansicht",
            "newsletter", "unbenannter artikel", "untitled", "notiz", "notizen",
            "email", "e-mail", "mail", "artikel", "post", "nachricht", "nachrichten", "document",
            "dokument", "update", "daily", "briefing", "morning briefing", "editorial",
            "thema des tages", "titelthema", "spotlight", "correctiv spotlight", "der tag auf einen blick",
            "grafik des tages", "faktencheck", "gute sache", "gute sachen",
            "im fokus", "fokus", "leitartikel", "kommentar", "hintergrund",
            "top-thema", "top thema", "schwerpunkt", "ausgabe", "inhaltsverzeichnis"
        )

        private val KICKER_PREFIX_REGEX = Regex(
            """^(?:\[[^\]]{2,30}\]\s*)?(?:(?:re|fwd|fw|wg|aw)\s*:\s*)?(thema des tages|titelthema|spotlight|correctiv spotlight|correctiv|editorial|daily|briefing|morning briefing|newsletter|ausgabe(?: vom [0-9\.\s\w]+)?|der tag auf einen blick|grafik des tages|faktencheck|gute sache\(n\)|im fokus|fokus|leitartikel|kommentar|hintergrund|top-thema|schwerpunkt|nachrichten|betreff|subject)\s*(?::|–|-|\|)\s*""",
            RegexOption.IGNORE_CASE
        )

        private val EMAIL_TAG_REGEX = Regex("""^\[[^\]]{2,30}\]\s*""")
        private val EMAIL_REPLY_FWD_REGEX = Regex("""^(?:re|fwd|fw|wg|aw)\s*:\s*""", RegexOption.IGNORE_CASE)
        private val TRAILING_SITE_SUFFIX_REGEX = Regex("""\s*(\||–|—|-)\s*[^|–—-]{2,60}$""")
        private val TRAILING_ELLIPSIS_REGEX = Regex("""\s*(…|\.{3,})$""")
    }

    fun hasKickerPrefix(text: String): Boolean {
        return KICKER_PREFIX_REGEX.containsMatchIn(text.trim())
    }

    fun cleanTitle(rawTitle: String?): String {
        if (rawTitle.isNullOrBlank()) return ""
        var t = rawTitle.trim()

        // 0. Strip zero-width and invisible characters
        t = t.replace(Regex("^[\\uFEFF\\u200B\\u200C\\u200D\\u2060]+"), "")
            .replace(Regex("[\\uFEFF\\u200B\\u200C\\u200D\\u2060]+$"), "")

        // 0b. Strip academic / multilingual galley view prefixes (e.g. "Anzeige von ", "View of ", "Affichage de ")
        t = t.replace(Regex("^(?:Anzeige von|View of|Affichage de)\\s+", RegexOption.IGNORE_CASE), "")

        // 1. Iteratively strip leading bracketed tags, reply/forward prefixes, and rubrics/kickers
        var changed = true
        while (changed) {
            val before = t
            t = EMAIL_TAG_REGEX.replace(t, "")
            t = EMAIL_REPLY_FWD_REGEX.replace(t, "")
            val kickerMatch = KICKER_PREFIX_REGEX.find(t)
            if (kickerMatch != null) {
                val stripped = t.substring(kickerMatch.range.last + 1).trim()
                if (stripped.isNotBlank()) {
                    t = stripped
                }
            }
            changed = (t != before)
        }

        // 2. Strip trailing site name or publisher suffix (e.g. " | CORRECTIV", " - SPIEGEL")
        t = TRAILING_SITE_SUFFIX_REGEX.replace(t, "").trim()

        // 3. Strip trailing ellipsis if present
        t = TRAILING_ELLIPSIS_REGEX.replace(t, "").trim()

        // 4. Strip quotation marks
        t = t.removeSurrounding("\"")
            .removeSurrounding("„", "“")
            .removeSurrounding("»", "«")
            .removeSurrounding("'")
            .trim()

        return t.ifBlank { rawTitle.trim() }
    }

    fun extractKickerFromDocument(doc: Document): String? {
        val candidates = doc.select("p, h1, h2, h3, div, td").filter {
            val t = it.text().trim()
            t.length in 10..150 && hasKickerPrefix(t)
        }
        for (el in candidates) {
            val cleaned = cleanTitle(el.text())
            if (cleaned.isNotBlank() && !isGenericTitle(cleaned) && cleaned.length in 5..100) {
                return cleaned
            }
        }
        return null
    }

    fun sanitizeDocumentTitle(candidate: String?): String {
        val defaultTitle = if (java.util.Locale.getDefault().language == "de") "Notiz" else "Note"
        if (candidate.isNullOrBlank()) return defaultTitle
        var clean = cleanTitle(candidate)
        if (clean.length > 100 || clean.split("\\s+".toRegex()).size > 15) {
            val firstSentence = clean.split(Regex("(?<=[.!?])\\s+|\\n+")).firstOrNull { it.isNotBlank() } ?: clean
            clean = if (firstSentence.length <= 100 && firstSentence.split("\\s+".toRegex()).size <= 15) {
                firstSentence.trimEnd('.', ' ', ':', '-')
            } else {
                clean.take(75).substringBeforeLast(" ", clean.take(75)).trimEnd('.', ' ', ':', '-')
            }
        }
        return clean.ifBlank { defaultTitle }
    }

    fun isGenericTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val clean = title.trim()
            .trimEnd('.', '…', ' ', ':', '-', '–')
            .lowercase()
        if (clean.length < 4) return true // Reject 1-3 letter strings like "Web"
        return GENERIC_TITLES.contains(clean)
    }

    fun extractTitleFromNewsletterWebLink(doc: org.jsoup.nodes.Document): String? {
        val linkKeywords = listOf(
            "browser", "webversion", "online lesen", "audioversion",
            "read online", "view in browser", "view online", "im web"
        )
        val candidates = doc.select("a[href]").filter { a ->
            val text = a.text().lowercase()
            val href = a.attr("href").lowercase()
            linkKeywords.any { text.contains(it) || href.contains(it) }
        }

        for (a in candidates) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val slug = extractTitleFromUrlSlug(href)
            if (slug != null && !isGenericTitle(slug)) {
                return slug
            }
        }

        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.contains("correctiv.org") || href.contains("/e/") || href.contains("article") || href.contains("post")) {
                val slug = extractTitleFromUrlSlug(href)
                if (slug != null && !isGenericTitle(slug) && slug.split(" ").size >= 3) {
                    return slug
                }
            }
        }
        return null
    }

    private fun isValidSlugWord(w: String): Boolean {
        if (w.contains("_") || w.length > 30) return false
        val hasLower = w.any { it.isLowerCase() }
        val hasUpper = w.any { it.isUpperCase() }
        val hasDigit = w.any { it.isDigit() }
        if (hasLower && hasUpper) return false
        if (hasDigit && hasUpper) return false
        return w.all { it.isLetterOrDigit() || it in "äöüÄÖÜß" }
    }

    fun extractTitleFromUrlSlug(url: String?): String? {
        if (url.isNullOrBlank()) return null
        try {
            val cleanUrl = url.trim().trimEnd(')', ']', '>', '.', ',', ';')
            val path = java.net.URI(cleanUrl).path ?: return null
            val segments = path.split("/").map { it.trim('-') }.filter { it.isNotBlank() }
            for (segment in segments.reversed()) {
                val clean = java.net.URLDecoder.decode(segment, "UTF-8")
                // Handle 20-delimited slugs commonly found in newsletter tracking URLs (e.g. 20die20Almancis20kommt20es20an)
                if (clean.matches(Regex("^(?:20[a-zA-ZäöüÄÖÜß]+)+$"))) {
                    val words = clean.split("20").filter { it.isNotBlank() && it.length > 1 }
                    if (words.size >= 2) {
                        return words.joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        }
                    }
                }
                if (clean.contains("-") && clean.length > 8 && !clean.matches(Regex("^[0-9a-fA-F-]+$"))) {
                    val words = clean.split("-").filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
                    if (words.size >= 2 && words.all { isValidSlugWord(it) }) {
                        return words.joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
