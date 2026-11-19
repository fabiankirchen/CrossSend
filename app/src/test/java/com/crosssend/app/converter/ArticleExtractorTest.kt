package com.crosssend.app.converter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ArticleExtractorTest {

    @Test
    fun testExtractFromVergeSnippet() = runBlocking {
        val sampleVergeHtml = """
            <body class="_1nfixzc0 _1044qiz0 duet--sticky-nav" data-duet-theme="vergeDark">
            <script>console.log("analytics");</script>
            <div id="__next">
                <main id="content">
                    <article>
                        <header class="duet--layout--header-pattern">
                            <aside id="popover-topic-breadcrumb" class="_1wu3rm0" aria-hidden="true">
                                <p>AICloseAIPosts from this topic</p>
                            </aside>
                            <span id="follow-author-author_byline_lead-123">
                                <span>Hayden Field</span>
                            </span>
                            <time datetime="2026-04-23T13:45:00+00:00">Apr 23, 2026, 3:45 PM GMT+2</time>
                            <h1>You’re about to feel the AI money squeeze</h1>
                        </header>
                        <figure>
                            <img src="https://platform.theverge.com/wp-content/uploads/sites/2/2026/04/vincentkilbride-theverge-ai-monetisation.jpg" alt="AI Money illustration" />
                            <figcaption>Vincent Kilbride / The Verge</figcaption>
                        </figure>
                        <p>Ads, rate limits, feature restrictions, price hikes. The AI free ride is over.</p>
                        <p>Earlier this month, millions of OpenClaw users woke up to a sweeping mandate: The viral AI agent tool would be heavily throttled.</p>
                        <div role="dialog" aria-modal="true" class="drawer">
                            <p>Subscribe now to read unlimited stories.</p>
                            <button>Sign in</button>
                        </div>
                    </article>
                </main>
            </div>
            </body>
        """.trimIndent()

        val extractor = ArticleExtractor()
        val article = extractor.extractFromHtml(sampleVergeHtml, "https://www.theverge.com")

        assertEquals("You’re about to feel the AI money squeeze", article.title)
        assertEquals("Ads, rate limits, feature restrictions, price hikes. The AI free ride is over.", article.subtitle)
        assertEquals("Hayden Field", article.author)
        assertNotNull(article.publishedDate)
        val cal = Calendar.getInstance().apply { time = article.publishedDate!! }
        assertEquals(2026, cal.get(Calendar.YEAR))

        assertNotNull(article.leadImageUrl)
        assertTrue(article.leadImageUrl!!.contains("vincentkilbride-theverge-ai-monetisation"))

        // Body should contain the real paragraphs and NOT the clutter
        assertTrue(article.contentHtml.contains("Earlier this month, millions of OpenClaw users"))
        assertTrue(!article.contentHtml.contains("AICloseAIPosts"))
        assertTrue(!article.contentHtml.contains("Subscribe now to read unlimited"))
    }

    @Test
    fun testExtractFromFullArticleFile() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("crosssend_test").toFile()
        try {
            val file = java.io.File(tempDir, "article.html")
            val sampleHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta property="og:title" content="You’re about to feel the AI money squeeze" />
                    <meta name="author" content="Hayden Field" />
                </head>
                <body>
                    <article>
                        <header>
                            <h1>You’re about to feel the AI money squeeze</h1>
                            <p>Ads, rate limits, feature restrictions, price hikes. The AI free ride is over.</p>
                            <span>Hayden Field</span>
                        </header>
                        <p>Earlier this month, millions of OpenClaw users woke up to a sweeping mandate: The viral AI agent tool would be heavily throttled.</p>
                        <p>Anthropic, like other leading AI labs, is preparing for higher compute requirements.</p>
                        <blockquote class="pullquote">
                            <p>When you sink trillions of dollars into data centers, you will see a market shift.</p>
                        </blockquote>
                    </article>
                </body>
                </html>
            """.trimIndent()
            file.writeText(sampleHtml)
            val html = file.readText()
            val extractor = ArticleExtractor()
            val article = extractor.extractFromHtml(html, "https://www.theverge.com/c/24128560/ai-price-hike-anthropic-openai")

            assertEquals("You’re about to feel the AI money squeeze", article.title)
            assertEquals("Ads, rate limits, feature restrictions, price hikes. The AI free ride is over.", article.subtitle)
            assertEquals("Hayden Field", article.author)
            assertTrue(article.contentHtml.contains("Earlier this month, millions of OpenClaw users"))
            assertTrue(article.contentHtml.contains("Anthropic, like other leading AI labs"))
            assertTrue(article.contentHtml.contains("When you sink trillions of dollars into data centers"))
            // Pullquote should be rendered with blockquote.pullquote
            assertTrue(article.contentHtml.contains("<blockquote class=\"pullquote\">"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSlugTitleExtraction() {
        val extractor = ArticleExtractor()
        val url = "https://go.correctiv.org/e/992892/-auf-die-almancis-kommt-es-an-/4c4ngm/1708578678/h/dummy_newsletter_token_12345"
        val slugTitle = extractor.extractTitleFromUrlSlug(url)
        assertEquals("Auf Die Almancis Kommt Es An", slugTitle)
    }

    @Test
    fun testCleanTitleAndKickerStripping() {
        val extractor = ArticleExtractor()

        assertEquals("Auf die Almancis kommt es an", extractor.cleanTitle("Thema des Tages: Auf die Almancis kommt es an"))
        assertEquals("Auf die Almancis kommt es an", extractor.cleanTitle("[CORRECTIV] Thema des Tages: Auf die Almancis kommt es an"))
        assertEquals("Auf die Almancis kommt es an", extractor.cleanTitle("Thema des Tages – Auf die Almancis kommt es an"))
        assertEquals("Auf die Almancis kommt es an", extractor.cleanTitle("Spotlight: Auf die Almancis kommt es an"))
        assertEquals("Auf die Almancis kommt es an", extractor.cleanTitle("Re: Fwd: [CORRECTIV] Auf die Almancis kommt es an | CORRECTIV"))

        assertTrue(extractor.isGenericTitle("Thema des Tages"))
        assertTrue(extractor.isGenericTitle("Thema des Tages…"))
        assertTrue(extractor.isGenericTitle("Thema des Tages..."))
        assertTrue(extractor.isGenericTitle("Spotlight"))
        assertTrue(extractor.isGenericTitle("Editorial"))
        assertTrue(extractor.isGenericTitle("Grafik des Tages"))
        assertTrue(!extractor.isGenericTitle("Auf die Almancis kommt es an"))
    }

    @Test
    fun testNewsletterHtmlTitleExtraction() = runBlocking {
        val extractor = ArticleExtractor()
        val newsletterHtml = """
            <html>
            <body>
                <p>Anette Dowideit</p>
                <p>Chefredakteurin</p>
                <a href="https://go.correctiv.org/e/992892/-auf-die-almancis-kommt-es-an-/4c4ngm/1708578678/h/dummy_newsletter_token_12345">Im Browser lesen und Kompaktfassung als Audioversion anhören</a>
                <h1>Thema des Tages</h1>
                <p>NRW-Ministerpräsident Hendrik Wüst (CDU) ist für drei Tage in der Türkei unterwegs...</p>
            </body>
            </html>
        """.trimIndent()

        val article = extractor.extractFromHtml(newsletterHtml, "https://crosssend.local")
        assertEquals("Auf Die Almancis Kommt Es An", article.title)
    }

    @Test
    fun testNewsletterWithRunawayHeadlineAndAuthor() = runBlocking {
        val extractor = ArticleExtractor()
        val newsletterHtml = """
            <html>
            <body>
                <p>Anette Dowideit</p>
                <p>Chefredakteurin</p>
                <a href="https://go.correctiv.org/e/992892/-auf-die-almancis-kommt-es-an-/4c4ngm/1708578678/h/dummy_newsletter_token_12345">Im Browser lesen und Kompaktfassung als Audioversion anhören</a>
                <div class="headline">
                    Thema des Tages NRW-Ministerpräsident Hendrik Wüst (der wiederum ebenfalls im öffentlichen Blick steht, schließlich wird er als möglicher Merz-Nachfolger gehandelt) seit heute für drei Tage in der Türkei unterwegs ist. Seine Reise gilt auch als Signal an die Deutschtürken in NRW – und in ganz Deutschland: So wichtig seid ihr für uns. Wo steht diese Bevölkerungsgruppe denn politisch – und was hat das mit der Angst der AfD vor einer angeblichen „ethnischen Wahl“ zu tun?
                </div>
                <p>Liebe Leserinnen und Leser, Sevgili okuyucular,</p>
                <p>wir starten mit einer Begrüßung auf Deutsch und Türkisch in die SPOTLIGHT-Woche...</p>
            </body>
            </html>
        """.trimIndent()

        val article = extractor.extractFromHtml(newsletterHtml, "https://crosssend.local")
        assertEquals("Auf Die Almancis Kommt Es An", article.title)
        assertEquals("Anette Dowideit", article.author)
        assertTrue(article.contentHtml.contains("Liebe Leserinnen und Leser"))
        assertTrue(article.contentHtml.contains("wir starten mit einer Begrüßung"))
    }

    @Test
    fun testEpubBuilderSanitizesGiantTitles() {
        val builder = EpubBuilder()
        val giantTitle = "Thema des Tages NRW-Ministerpräsident Hendrik Wüst (der wiederum ebenfalls im öffentlichen Blick steht, schließlich wird er als möglicher Merz-Nachfolger gehandelt) seit heute für drei Tage in der Türkei unterwegs ist."
        val safe = builder.sanitizeTitleForEpub(giantTitle)
        assertTrue(safe.length <= 100)
        assertTrue(!safe.endsWith("."))
    }

    @Test
    fun testEpubBuilderEndToEndVerification() = runBlocking {
        val builder = EpubBuilder()
        val tempFile = java.io.File.createTempFile("test_newsletter", ".epub")
        try {
            val contentHtml = """
                <h2>Thema des Tages NRW-Ministerpräsident Hendrik Wüst (der wiederum ebenfalls im öffentlichen Blick steht, schließlich wird er als möglicher Merz-Nachfolger gehandelt) seit heute für drei Tage in der Türkei unterwegs ist. Seine Reise gilt auch als Signal an die Deutschtürken in NRW – und in ganz Deutschland: So wichtig seid ihr für uns. Wo steht diese Bevölkerungsgruppe denn politisch – und was hat das mit der Angst der AfD vor einer angeblichen „ethnischen Wahl“ zu tun?</h2>
                <p>Liebe Leserinnen und Leser, Sevgili okuyucular,</p>
                <p>wir starten mit einer Begrüßung auf Deutsch und Türkisch in die SPOTLIGHT-Woche...</p>
            """.trimIndent()

            val success = builder.buildEpub(
                title = "Auf Die Almancis Kommt Es An",
                author = "Anette Dowideit",
                sourceUrl = "https://go.correctiv.org/e/992892/-auf-die-almancis-kommt-es-an-/4c4ngm/1708578678/h/dummy_newsletter_token_12345",
                contentHtml = contentHtml,
                coverImage = null,
                inlineImages = emptyList(),
                outputFile = tempFile,
                wordCount = 200,
                readingTimeMinutes = 1
            )
            assertTrue(success)

            // Inspect the generated EPUB zip contents
            val zip = java.util.zip.ZipFile(tempFile)
            val chapterEntry = zip.getEntry("OEBPS/chapter_1.xhtml")
            assertNotNull(chapterEntry)
            val chapterContent = zip.getInputStream(chapterEntry).bufferedReader().readText()

            // Verify title is rendered cleanly in h1
            assertTrue(chapterContent.contains("<h1 class=\"article-title\">Auf Die Almancis Kommt Es An</h1>"))
            // Verify author byline
            assertTrue(chapterContent.contains("Anette Dowideit"))
            // Verify first paragraphs are preserved and NOT stripped
            assertTrue(chapterContent.contains("Liebe Leserinnen und Leser"))
            assertTrue(chapterContent.contains("wir starten mit einer Begrüßung"))
            // Verify runaway h2 was split into short h2 and normal paragraphs
            val doc = org.jsoup.Jsoup.parse(chapterContent)
            for (h2 in doc.select("h2")) {
                assertTrue(h2.text().length <= 100)
            }
            assertTrue(doc.select("p").size >= 3)
            zip.close()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testAcademicViewerPrefixAndZeroWidthStripping() {
        val extractor = ArticleExtractor()
        val rawTitle = "\uFEFFAnzeige von Das generische Maskulinum im Deutschen | Linguistik Online"
        val cleaned = extractor.cleanTitle(rawTitle)
        assertEquals("Das generische Maskulinum im Deutschen", cleaned)

        val englishTitle = "View of Machine Learning in Linguistics | Journal"
        assertEquals("Machine Learning in Linguistics", extractor.cleanTitle(englishTitle))

        val frenchTitle = "Affichage de L'intelligence artificielle | Revue"
        assertEquals("L'intelligence artificielle", extractor.cleanTitle(frenchTitle))
    }

    @Test
    fun testCanonicalImageUrlDeduplication() = runBlocking {
        val extractor = ArticleExtractor()
        val sampleHtml = """
            <head>
                <meta name="author" content="David Pierce" />
            </head>
            <article>
                <h1>Test Article</h1>
                <p><img src="https://platform.theverge.com/image.jpg?crop=0,0,100,100&w=540" alt="Hero" /></p>
                <p><img src="https://platform.theverge.com/image.jpg?crop=16,0,66,100&w=540" alt="Hero variant" /></p>
                <p>by David PierceApr 23, 2026</p>
                <p>David Pierce is editor-at-large covering tech.</p>
                <p>This is the first real paragraph of the test article which contains plenty of meaningful sentences to surpass the threshold.</p>
                <p>This is the second real paragraph with lots of great narrative information that must be preserved entirely.</p>
                <p><img src="https://platform.theverge.com/different_diagram.png?w=600" alt="Diagram" /></p>
                <p>Final concluding paragraph of the story.</p>
                <p><a href="https://theverge.com/share">Share</a></p>
                <ul>
                    <li>David Pierce</li>
                </ul>
            </article>
        """.trimIndent()

        val article = extractor.extractFromHtml(sampleHtml, "https://www.theverge.com/test")

        // First image should be recognized as leadImage, and its duplicate crop in body should be skipped!
        assertEquals("David Pierce", article.author)
        // Body should NOT contain duplicate hero image
        val occurrenceCount = article.contentHtml.split("image.jpg").size - 1
        assertEquals(0, occurrenceCount) // It matched leadImage, so neither crop is duplicated into body!

        // Inline image different_diagram should be preserved
        assertTrue(article.contentHtml.contains("different_diagram.png"))

        // Leading byline, photo credits, and author bio should be removed
        assertTrue(!article.contentHtml.contains("David Pierce is editor-at-large"))
        assertTrue(!article.contentHtml.contains("by David PierceApr 23"))

        // Real text should be present
        assertTrue(article.contentHtml.contains("This is the first real paragraph"))
        assertTrue(article.contentHtml.contains("This is the second real paragraph"))
        assertTrue(article.contentHtml.contains("Final concluding paragraph"))

        // Trailing Share link and trailing author list should be removed
        assertTrue(!article.contentHtml.contains("Share</a>"))
        assertTrue(!article.contentHtml.contains("<li>David Pierce</li>"))
    }

    @Test
    fun testZeroWidthSanitizationInEpubBuilder() {
        val builder = EpubBuilder()
        val textWithZeroWidth = "\uFEFFThe Verge\u200B spoke to &nbsp;mathematicians\u200C."
        val cleaned = builder.sanitizeTextForEpub(textWithZeroWidth)
        assertEquals("The Verge spoke to  mathematicians.", cleaned)
    }

    @Test
    fun testSubstackUndertowExtraction() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("crosssend_test").toFile()
        try {
            val file = java.io.File(tempDir, "content.md")
            val sampleMarkdown = """
                Title: Live Content
                Description: Fetched live
                Source: https://askastrid.substack.com/p/the-undertow
                
                ---
                
                <!DOCTYPE html>
                <html>
                <head>
                    <title>The undertow - Astrid</title>
                    <meta property="og:title" content="The undertow" />
                    <meta property="og:description" content="Women don't avoid sovereign hobbies by accident; they get pulled back before they ever reach the water" />
                    <meta name="author" content="Astrid" />
                    <meta property="og:image" content="https://substackcdn.com/image/hero.png" />
                </head>
                <body>
                    <div class="post-header">
                        <h1 class="post-title">The undertow</h1>
                        <h3 class="subtitle">Women don't avoid sovereign hobbies by accident; they get pulled back before they ever reach the water</h3>
                        <div class="byline-wrapper"><span class="byline"><a rel="author">Astrid</a></span></div>
                    </div>
                    <div class="body markup">
                        <p>The first sovereign hobby was not surfing or carpentry; it was walking alone by the shore with thoughts undisturbed.</p>
                        <p>When women speak of wanting space, what they often mean is uninterrupted attention to the world around them.</p>
                        <p>The tide moves slowly at first, gentle ripples giving way to deep current that carries everything away.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()
            file.writeText(sampleMarkdown)
            assertTrue(file.exists())
            val rawHtml = file.readText().substringAfter("---\n\n")
            val extractor = ArticleExtractor()
            val article = extractor.extractFromHtml(rawHtml, "https://askastrid.substack.com/p/the-undertow")
            assertEquals("The undertow", article.title)
            assertTrue(article.author?.contains("Astrid") == true)
            assertTrue(article.wordCount > 10)
            val epubBuilder = EpubBuilder()
            val tempEpub = java.io.File(tempDir, "undertow.epub")
            val built = epubBuilder.buildEpub(
                title = article.title,
                subtitle = article.subtitle,
                author = article.author,
                sourceUrl = article.sourceUrl,
                contentHtml = article.contentHtml,
                coverImage = null,
                inlineImages = emptyList(),
                outputFile = tempEpub,
                wordCount = article.wordCount,
                readingTimeMinutes = article.readingTimeMinutes,
                publishedDate = article.publishedDate
            )
            assertTrue(built)
            assertTrue(tempEpub.exists())
            assertTrue(tempEpub.length() > 0)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testTagesschauHtmlExtractionOffline() = runBlocking {
        val sampleTagesschauHtml = """
            <!DOCTYPE html>
            <html lang="de">
            <head>
                <meta charset="utf-8">
                <title>Erneuerbare Energien: Rekordanteil am deutschen Stromverbrauch | tagesschau.de</title>
                <meta property="og:title" content="Erneuerbare Energien: Rekordanteil am deutschen Stromverbrauch" />
                <meta property="og:description" content="Wind und Sonne deckten im ersten Halbjahr mehr als die Hälfte des deutschen Strombedarfs ab." />
                <meta property="og:site_name" content="tagesschau.de" />
                <meta property="og:image" content="https://images.tagesschau.de/image/123/test.jpg" />
                <meta name="author" content="Tagesschau Redaktion" />
                <meta name="date" content="2026-05-15T12:00:00+02:00" />
            </head>
            <body>
                <main>
                    <article class="article-content">
                        <header>
                            <span class="kicker">Energiewende in Deutschland</span>
                            <h1 class="article-headline">Rekordanteil erneuerbarer Energien am Stromverbrauch</h1>
                            <p class="article-summary">Wind und Sonne deckten im ersten Halbjahr mehr als die Hälfte des deutschen Strombedarfs ab.</p>
                            <div class="byline">Von Tagesschau Redaktion, Stand: 15.05.2026 12:00 Uhr</div>
                        </header>
                        <figure>
                            <picture>
                                <source srcset="https://images.tagesschau.de/image/123/test.jpg" type="image/jpeg">
                                <img src="https://images.tagesschau.de/image/123/test.jpg" alt="Windräder in einer Landschaft bei Sonnenuntergang" />
                            </picture>
                            <figcaption>Windräder produzierten so viel Strom wie noch nie zuvor.</figcaption>
                        </figure>
                        <p>Die erneuerbaren Energien haben in Deutschland einen neuen Meilenstein erreicht. Im ersten Halbjahr dieses Jahres stammte über 55 Prozent des verbrauchten Stroms aus Windkraft, Photovoltaik, Biomasse und Wasserkraft.</p>
                        <p>Besonders die Windenergie an Land und auf See verzeichnete dank günstiger Wetterbedingungen deutliche Zuwächse gegenüber dem Vorjahreszeitraum.</p>
                        <p>Expertinnen und Experten betonen jedoch, dass für das Erreichen der Klimaziele der Netzausbau und die Speicherkapazitäten nun noch zügiger vorangetrieben werden müssen.</p>
                    </article>
                </main>
            </body>
            </html>
        """.trimIndent()

        val extractor = ArticleExtractor()
        val article = extractor.extractFromHtml(sampleTagesschauHtml, "https://www.tagesschau.de/inland/wirtschaft/erneuerbare-energien-strom-100.html")

        assertNotNull(article)
        assertTrue(article.title.isNotBlank())
        assertTrue(article.title.contains("Rekordanteil") || article.title.contains("Erneuerbare Energien"))
        assertNotNull(article.leadImageUrl)
        assertEquals("https://images.tagesschau.de/image/123/test.jpg", article.leadImageUrl)
        assertTrue(article.contentHtml.contains("Die erneuerbaren Energien haben in Deutschland"))
        assertTrue(article.contentHtml.contains("Windkraft, Photovoltaik"))
        assertTrue(article.wordCount > 30)
    }

    @Test
    fun testSanitizeInlineFormattingStripsEventHandlersAndDangerousUrls() = runBlocking {
        val maliciousHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Security Sanitizer Test</title>
            </head>
            <body>
                <article>
                    <h1>Security Sanitizer Test</h1>
                    <p class="deck">Sanitizing untrusted article markup while preserving legitimate formatting.</p>
                    <p>
                        This paragraph includes <strong>bold text</strong>, <em>italic text</em>, and a
                        <a href="https://example.com/safe" onclick="alert(1)" onmouseover="alert(2)">valid link</a>
                        to ensure safe formatting stays intact while reading the full story.
                    </p>
                    <p>
                        Malicious handlers like <span onload="alert(1)" onerror="alert(2)" onmouseover="steal()">safe span text</span>
                        and <a href="javascript:alert(1)">js link</a> or <a href="  java&#x09;script:alert(2)">obfuscated js</a>
                        or <a href="data:text/html,<script>alert(1)</script>">data link</a>
                        or <a href="vbscript:msgbox(1)">vbs link</a> must be neutralized cleanly.
                    </p>
                    <p>
                        Embedded elements like <iframe src="https://evil.example/frame">frame fallback</iframe>,
                        <object data="https://evil.example/obj"></object>, <embed src="https://evil.example/emb" />,
                        and <form action="https://evil.example/post"><input type="text" value="x" /><button>Go</button></form>
                        are removed while surrounding prose remains readable.
                    </p>
                </article>
            </body>
            </html>
        """.trimIndent()

        val extractor = ArticleExtractor()
        val article = extractor.extractFromHtml(maliciousHtml, "https://example.com/article")

        assertTrue(article.contentHtml.contains("<strong>bold text</strong>"))
        assertTrue(article.contentHtml.contains("<em>italic text</em>"))
        assertTrue(article.contentHtml.contains("<a href=\"https://example.com/safe\">valid link</a>"))
        assertTrue(article.contentHtml.contains("safe span text"))

        val lowerHtml = article.contentHtml.lowercase()
        assertTrue(!lowerHtml.contains("onload"))
        assertTrue(!lowerHtml.contains("onerror"))
        assertTrue(!lowerHtml.contains("onmouseover"))
        assertTrue(!lowerHtml.contains("onclick"))
        assertTrue(!lowerHtml.contains("javascript:"))
        assertTrue(!lowerHtml.contains("vbscript:"))
        assertTrue(!lowerHtml.contains("data:text/html"))
        assertTrue(!lowerHtml.contains("<iframe"))
        assertTrue(!lowerHtml.contains("<object"))
        assertTrue(!lowerHtml.contains("<embed"))
        assertTrue(!lowerHtml.contains("<form"))
        assertTrue(!lowerHtml.contains("<input"))
        assertTrue(!lowerHtml.contains("<button"))
    }

    @Test
    fun testRssFeedParserSanitizesUnsafeMarkupAndEscapesTitle() {
        val parser = com.crosssend.app.network.RssFeedParser()
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>Security Test Feed</title>
                <item>
                  <title>Malicious &lt;img src=x onerror=alert(1)&gt; Title</title>
                  <link>https://example.com/1</link>
                </item>
                <item>
                  <title>XSS Vector Item</title>
                  <link>https://example.com/2</link>
                  <description><![CDATA[
                    <p>Readable <a href="https://example.com/ok" formaction="javascript:alert(1)">article</a> paragraph.</p>
                    <svg onload="alert(2)"><a xlink:href="javascript:alert(3)">bad</a></svg>
                    <math><mi>x</mi></math>
                    <video src="https://evil.example/v.mp4"></video>
                    <a href="java&#x09;script:alert(4)">bad link</a>
                  ]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = parser.parseFeedXml(rssXml, "https://example.com/feed.xml")
        assertEquals(2, parsed.items.size)

        val item1Html = parsed.items[0].contentHtml
        assertTrue(!item1Html.contains("<img"))
        assertTrue(item1Html.contains("&lt;img"))

        val item2Html = parsed.items[1].contentHtml.lowercase()
        assertTrue(item2Html.contains("https://example.com/ok"))
        assertTrue(!item2Html.contains("<svg"))
        assertTrue(!item2Html.contains("<math"))
        assertTrue(!item2Html.contains("<video"))
        assertTrue(!item2Html.contains("onload"))
        assertTrue(!item2Html.contains("formaction"))
        assertTrue(!item2Html.contains("javascript:"))
    }

    @Test
    fun testSanitizersEnforceUriSchemeAllowlistInArticlesAndFeeds() = runBlocking {
        val extractor = ArticleExtractor()
        val articleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <article>
                    <h1>URI Allowlist Test</h1>
                    <p>First real paragraph with enough narrative words so extraction keeps the main article body intact.</p>
                    <p>
                        Allowed links:
                        <a href="https://example.com/full">https link</a>,
                        <a href="http://example.com/plain">http link</a>,
                        <a href="#section-1">anchor link</a>,
                        <a href="/articles/next">relative link</a>.
                    </p>
                    <p>
                        Blocked links:
                        <a href="file:///etc/passwd">file link</a>,
                        <a href="intent://scan/#Intent;scheme=zxing;end">intent link</a>,
                        <a href="javascript:alert(1)">js link</a>,
                        <a href=" data:text/html,&lt;script&gt;alert(1)&lt;/script&gt;">data link</a>,
                        <a href="//evil.example/proto-rel">proto relative</a>.
                    </p>
                </article>
            </body>
            </html>
        """.trimIndent()

        val article = extractor.extractFromHtml(articleHtml, "https://example.com/post")
        val content = article.contentHtml
        assertTrue(content.contains("href=\"https://example.com/full\""))
        assertTrue(content.contains("href=\"http://example.com/plain\""))
        assertTrue(content.contains("href=\"#section-1\""))
        assertTrue(content.contains("href=\"/articles/next\""))
        assertTrue(!content.contains("file://"))
        assertTrue(!content.contains("intent:"))
        assertTrue(!content.contains("javascript:"))
        assertTrue(!content.contains("data:text/html"))
        assertTrue(!content.contains("//evil.example"))

        val parser = com.crosssend.app.network.RssFeedParser()
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0">
              <channel>
                <title>URI Allowlist Feed</title>
                <item>
                  <title>Links and Images</title>
                  <link>https://example.com/1</link>
                  <description><![CDATA[
                    <p>
                      <a href="https://example.com/a">https</a>
                      <a href="http://example.com/b">http</a>
                      <a href="#top">anchor</a>
                      <a href="/local/path">path</a>
                      <a href="file:///etc/shadow">file</a>
                      <a href="intent://evil">intent</a>
                      <a href=" data:text/html,xss">data</a>
                    </p>
                    <img src="https://cdn.example.com/good.jpg" alt="good" />
                    <img src=" data:image/svg+xml,<svg onload=alert(1)/>" alt="obfuscated data" />
                    <img src="javascript:alert(1)" alt="js img" />
                    <img src="file:///etc/passwd" alt="file img" />
                    <img src="/relative-img.png" alt="rel img" />
                  ]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseFeedXml(rssXml, "https://example.com/feed.xml")
        val feedHtml = feed.items.single().contentHtml
        assertTrue(feedHtml.contains("href=\"https://example.com/a\""))
        assertTrue(feedHtml.contains("href=\"http://example.com/b\""))
        assertTrue(feedHtml.contains("href=\"#top\""))
        assertTrue(feedHtml.contains("href=\"/local/path\""))
        assertTrue(feedHtml.contains("src=\"https://cdn.example.com/good.jpg\""))

        assertTrue(!feedHtml.contains("file://"))
        assertTrue(!feedHtml.contains("intent:"))
        assertTrue(!feedHtml.contains("data:text/html"))
        assertTrue(!feedHtml.contains("obfuscated data"))
        assertTrue(!feedHtml.contains("js img"))
        assertTrue(!feedHtml.contains("file img"))
        assertTrue(!feedHtml.contains("rel img"))
    }
}

