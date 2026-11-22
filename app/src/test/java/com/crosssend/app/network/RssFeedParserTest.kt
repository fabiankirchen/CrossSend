package com.crosssend.app.network

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssFeedParserTest {

    private val parser = RssFeedParser()

    @Test
    fun `parseFeedXml filters tracking pixels by dimensions, CSS style, host, and path`() {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Example Feed</title>
                <link>https://example.com/</link>
                <item>
                  <title>Hero Image Article</title>
                  <link>https://example.com/articles/1</link>
                  <content:encoded><![CDATA[
                    <p>Lead paragraph.</p>
                    <img src="https://cdn.example.com/photo.jpg?w=800&utm_source=feed" alt="Hero" />
                    <img src="https://ads.example.com/1x1.png" width="1" height="1" />
                    <img src="https://pixel.wp.com/b.gif" />
                    <img src="https://feeds.feedburner.com/~r/test/~4/abc" />
                    <img src="https://example.com/hid.png" style="display: none; width: 10px" />
                  ]]></content:encoded>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseFeedXml(rssXml, "https://example.com/rss.xml")
        assertEquals(1, feed.items.size)

        val item = feed.items.single()
        assertEquals(
            listOf("https://cdn.example.com/photo.jpg?w=800"),
            item.imageUrls
        )

        val parsedContent = Jsoup.parseBodyFragment(item.contentHtml)
        val renderedImages = parsedContent.select("img")
        assertEquals(1, renderedImages.size)
        assertEquals(
            "https://cdn.example.com/photo.jpg?w=800",
            renderedImages.single().attr("src")
        )
        assertFalse(item.contentHtml.contains("ads.example.com"))
        assertFalse(item.contentHtml.contains("pixel.wp.com"))
        assertFalse(item.contentHtml.contains("feeds.feedburner.com"))
        assertFalse(item.contentHtml.contains("hid.png"))
    }

    @Test
    fun `parseFeedXml enforces strict tag and attribute allowlist and strips tracking params`() {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Security Feed</title>
                <link>https://example.com/?utm_source=nav</link>
                <item>
                  <title>Sanitized Story</title>
                  <link>https://example.com/story?id=9&amp;utm_campaign=rss&amp;fbclid=xyz</link>
                  <content:encoded><![CDATA[
                    <p data-track="1" style="color:red" onclick="evil()"><font size="3"><a href="https://example.com/story?id=9&utm_campaign=rss&fbclid=xyz" ping="https://track.example/ping" rel="sponsored" class="btn">Read more</a></font></p>
                    <img src="https://cdn.example.com/cover.jpg?size=large&utm_medium=rss" alt="Cover" srcset="https://cdn.example.com/cover-2x.jpg 2x" loading="lazy" onclick="evil()" onerror="evil()" />
                    <script>alert('xss')</script>
                  ]]></content:encoded>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseFeedXml(rssXml, "https://example.com/rss.xml")
        assertEquals("https://example.com/", feed.link)
        assertEquals(1, feed.items.size)

        val item = feed.items.single()
        assertEquals("https://example.com/story?id=9", item.link)

        val doc = Jsoup.parseBodyFragment(item.contentHtml)
        assertTrue(doc.select("font").isEmpty())
        assertTrue(doc.select("script").isEmpty())

        val paragraphs = doc.select("p")
        assertEquals(1, paragraphs.size)
        assertTrue(paragraphs.single().attributes().asList().isEmpty())

        val links = doc.select("a")
        assertEquals(1, links.size)
        val anchor = links.single()
        assertEquals("Read more", anchor.text())
        assertEquals("https://example.com/story?id=9", anchor.attr("href"))
        assertEquals(listOf("href"), anchor.attributes().asList().map { it.key })

        val images = doc.select("img")
        assertEquals(1, images.size)
        val img = images.single()
        assertEquals("https://cdn.example.com/cover.jpg?size=large", img.attr("src"))
        assertEquals("Cover", img.attr("alt"))
        assertEquals(
            listOf("alt", "src"),
            img.attributes().asList().map { it.key }.sorted()
        )
    }

    @Test
    fun `parseFeedXml parses Atom feed and drops relative and unsafe image URIs`() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Tech Atom Feed</title>
              <subtitle>Engineering notes</subtitle>
              <link rel="alternate" href="https://atom.example.com/?utm_source=atom" />
              <entry>
                <title>Atom Entry Title</title>
                <link rel="alternate" href="https://atom.example.com/post?page=2&amp;utm_source=atom&amp;gclid=abc" />
                <link rel="enclosure" href="https://cdn.example.com/attachment.mp3" type="audio/mpeg" />
                <author>
                  <name>Jane Doe</name>
                </author>
                <published>2026-10-15T12:30:00Z</published>
                <summary>Concise Atom summary text.</summary>
                <content type="html"><![CDATA[
                  <p>Atom body text.</p>
                  <img src="https://cdn.example.com/atom-hero.jpg?w=640&utm_campaign=fall" alt="Atom Hero" />
                  <img src="/thumb.png" alt="Relative" />
                  <img src="data:image/png;base64,iVBORw0KGgo=" alt="Data" />
                  <img src="javascript:alert(1)" alt="JS" />
                ]]></content>
              </entry>
            </feed>
        """.trimIndent()

        val feed = parser.parseFeedXml(atomXml, "https://atom.example.com/feed.xml")
        assertEquals("Tech Atom Feed", feed.title)
        assertEquals("Engineering notes", feed.description)
        assertEquals("https://atom.example.com/", feed.link)
        assertEquals(1, feed.items.size)

        val item = feed.items.single()
        assertEquals("Atom Entry Title", item.title)
        assertEquals("https://atom.example.com/post?page=2", item.link)
        assertEquals("Jane Doe", item.author)
        assertEquals("Concise Atom summary text.", item.description)
        assertNotNull(item.publishedDate)
        assertEquals(
            listOf("https://cdn.example.com/atom-hero.jpg?w=640"),
            item.imageUrls
        )

        val renderedImages = Jsoup.parseBodyFragment(item.contentHtml).select("img")
        assertEquals(1, renderedImages.size)
        assertEquals(
            "https://cdn.example.com/atom-hero.jpg?w=640",
            renderedImages.single().attr("src")
        )
        assertFalse(item.contentHtml.contains("/thumb.png"))
        assertFalse(item.contentHtml.contains("data:image/png"))
        assertFalse(item.contentHtml.contains("javascript:"))
    }

    @Test
    fun `parseFeedXml ignores podcast audio enclosure in imageUrls while keeping image enclosures`() {
        val podcastRssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Podcast Feed</title>
                <item>
                  <title>Episode 1</title>
                  <link>https://podcast.example.com/ep1</link>
                  <description>Audio only episode.</description>
                  <enclosure url="https://cdn.example.com/ep1.mp3" length="123456" type="audio/mpeg" />
                </item>
                <item>
                  <title>Episode 2</title>
                  <link>https://podcast.example.com/ep2</link>
                  <description>Episode with artwork and video.</description>
                  <enclosure url="https://cdn.example.com/ep2.mp4" length="999999" type="video/mp4" />
                  <enclosure url="https://cdn.example.com/ep2-cover.jpg?utm_source=podcast" length="54321" type="image/jpeg" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parseFeedXml(podcastRssXml, "https://podcast.example.com/rss.xml")
        assertEquals(2, feed.items.size)

        val audioOnlyItem = feed.items[0]
        assertTrue(audioOnlyItem.imageUrls.isEmpty())
        assertFalse(audioOnlyItem.imageUrls.contains("https://cdn.example.com/ep1.mp3"))

        val artworkItem = feed.items[1]
        assertEquals(
            listOf("https://cdn.example.com/ep2-cover.jpg"),
            artworkItem.imageUrls
        )
    }
}
