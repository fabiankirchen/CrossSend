package com.crosssend.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun isTrackingParam_matchesPrefixesAndExactNamesCaseInsensitively() {
        assertTrue(UrlSanitizer.isTrackingParam("utm_source"))
        assertTrue(UrlSanitizer.isTrackingParam("UTM_MEDIUM"))
        assertTrue(UrlSanitizer.isTrackingParam("mtm_campaign"))
        assertTrue(UrlSanitizer.isTrackingParam("pk_kwd"))
        assertTrue(UrlSanitizer.isTrackingParam("piwik_campaign"))
        assertTrue(UrlSanitizer.isTrackingParam("at_medium"))
        assertTrue(UrlSanitizer.isTrackingParam("wt_mc"))
        assertTrue(UrlSanitizer.isTrackingParam("fb_action_ids"))
        assertTrue(UrlSanitizer.isTrackingParam("mc_cid"))
        assertTrue(UrlSanitizer.isTrackingParam("mc_eid"))
        assertTrue(UrlSanitizer.isTrackingParam("oly_anon_id"))
        assertTrue(UrlSanitizer.isTrackingParam("vero_id"))
        assertTrue(UrlSanitizer.isTrackingParam("_hsenc"))
        assertTrue(UrlSanitizer.isTrackingParam("guce_referrer"))

        assertTrue(UrlSanitizer.isTrackingParam("fbclid"))
        assertTrue(UrlSanitizer.isTrackingParam("FbClId"))
        assertTrue(UrlSanitizer.isTrackingParam("gclid"))
        assertTrue(UrlSanitizer.isTrackingParam("igshid"))
        assertTrue(UrlSanitizer.isTrackingParam("twclid"))
        assertTrue(UrlSanitizer.isTrackingParam("msclkid"))
        assertTrue(UrlSanitizer.isTrackingParam("si"))
        assertTrue(UrlSanitizer.isTrackingParam("algo_pvid"))

        assertFalse(UrlSanitizer.isTrackingParam(""))
        assertFalse(UrlSanitizer.isTrackingParam("id"))
        assertFalse(UrlSanitizer.isTrackingParam("page"))
        assertFalse(UrlSanitizer.isTrackingParam("v"))
        assertFalse(UrlSanitizer.isTrackingParam("story_id"))
        assertFalse(UrlSanitizer.isTrackingParam("site"))
    }

    @Test
    fun stripTrackingParams_removesCommonTrackingParams() {
        val raw = "https://example.com/news/story" +
            "?utm_source=newsletter&utm_medium=email&utm_campaign=weekly" +
            "&fbclid=fb123&gclid=gc456&igshid=ig789&mc_cid=mc1&mc_eid=mc2" +
            "&twclid=tw1&msclkid=ms1&_hsenc=hs1&si=yt1"

        assertEquals(
            "https://example.com/news/story",
            UrlSanitizer.stripTrackingParams(raw)
        )
    }

    @Test
    fun stripTrackingParams_preservesFunctionalParamsDuplicatesAndFragment() {
        assertEquals(
            "https://example.com/article?id=42&page=3#section-2",
            UrlSanitizer.stripTrackingParams(
                "https://example.com/article?id=42&utm_source=rss&page=3&fbclid=abc#section-2"
            )
        )

        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            UrlSanitizer.stripTrackingParams(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=sharetoken123"
            )
        )

        assertEquals(
            "https://example.com/search?tag=a&tag=b&q=eink&lang=de&article=9&story_id=77&p=2&s=top",
            UrlSanitizer.stripTrackingParams(
                "https://example.com/search?tag=a&utm_term=eink&tag=b&q=eink&lang=de&article=9&story_id=77&p=2&s=top&gclid=xyz"
            )
        )
    }

    @Test
    fun stripTrackingParams_matchesCaseInsensitiveQueryParamNames() {
        val raw = "https://example.com/post?UTM_SOURCE=feed&FbClId=AbC123&id=99"
        assertEquals(
            "https://example.com/post?id=99",
            UrlSanitizer.stripTrackingParams(raw)
        )
    }

    @Test
    fun stripTrackingParams_handlesSafeRelativeUrls() {
        assertEquals(
            "/articles/post?page=2#top",
            UrlSanitizer.stripTrackingParams("/articles/post?utm_source=rss&page=2#top")
        )
        assertEquals(
            "/articles/post#sec%201",
            UrlSanitizer.stripTrackingParams("  /articles/post?fbclid=xyz#sec%201  ")
        )
        assertEquals(
            "/articles/post?id=5",
            UrlSanitizer.stripTrackingParams("/articles/post?id=5")
        )
    }

    @Test
    fun stripTrackingParams_leavesNonHttpAndProtocolRelativeUrlsUntouched() {
        assertEquals(
            "mailto:test@example.com",
            UrlSanitizer.stripTrackingParams("  mailto:test@example.com  ")
        )
        assertEquals(
            "content://media/1",
            UrlSanitizer.stripTrackingParams("content://media/1")
        )
        assertEquals(
            "javascript:void(0)",
            UrlSanitizer.stripTrackingParams("javascript:void(0)")
        )
        assertEquals(
            "data:text/plain;base64,SGVsbG8=",
            UrlSanitizer.stripTrackingParams("data:text/plain;base64,SGVsbG8=")
        )
        assertEquals(
            "file:///storage/emulated/0/book.epub",
            UrlSanitizer.stripTrackingParams("file:///storage/emulated/0/book.epub")
        )
        assertEquals(
            "//cdn.example.com/post?utm_source=x",
            UrlSanitizer.stripTrackingParams("//cdn.example.com/post?utm_source=x")
        )
    }

    @Test
    fun stripTrackingParams_handlesBlankAndMalformedInputsSafely() {
        assertEquals("", UrlSanitizer.stripTrackingParams(""))
        assertEquals("", UrlSanitizer.stripTrackingParams("   \t\n  "))
        assertEquals("not a url", UrlSanitizer.stripTrackingParams("  not a url  "))
        assertEquals("ht!tp://broken", UrlSanitizer.stripTrackingParams("ht!tp://broken"))
    }
}
