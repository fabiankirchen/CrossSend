package com.crosssend.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLoggerTest {

    @Test
    fun `redactUrl strips credentials path query and fragment while preserving scheme host and non-default port`() {
        val raw = "https://alice:s3cr3t@news.example.com:8443/2026/11/private-article-slug?token=xyz123&uid=42#comments"
        assertEquals("https://news.example.com:8443/...", SafeLogger.redactUrl(raw))
    }

    @Test
    fun `redactUrl omits default ports and formats root-only URLs without ellipsis`() {
        assertEquals("https://example.com/", SafeLogger.redactUrl("https://example.com:443"))
        assertEquals("https://example.com/", SafeLogger.redactUrl("https://example.com/?token=secret#frag"))
        assertEquals("http://192.168.4.1/", SafeLogger.redactUrl("http://192.168.4.1:80/"))
        assertEquals("http://192.168.4.1:8080/...", SafeLogger.redactUrl("http://192.168.4.1:8080/api/status?k=v"))
    }

    @Test
    fun `redactUrl handles content file mailto data null blank and invalid inputs`() {
        assertEquals("<empty>", SafeLogger.redactUrl(null))
        assertEquals("<empty>", SafeLogger.redactUrl("   "))

        assertEquals(
            "content://com.android.providers.downloads.documents/...",
            SafeLogger.redactUrl("content://com.android.providers.downloads.documents/document/raw%3A123?token=abc")
        )
        assertEquals(
            "content://com.example.provider/...",
            SafeLogger.redactUrl("content://user:pass@com.example.provider/items/1")
        )
        assertEquals("content:<redacted>", SafeLogger.redactUrl("content:///document/1"))

        assertEquals("file:<redacted>", SafeLogger.redactUrl("file:///data/user/0/com.crosssend.app/files/book.epub"))
        assertEquals("mailto:<redacted>", SafeLogger.redactUrl("MAILTO:reader@example.com?subject=hi"))
        assertEquals("data:<redacted>", SafeLogger.redactUrl("data:text/html,<script>alert(1)</script>"))

        assertEquals("<invalid-url>", SafeLogger.redactUrl("not a url"))
        assertEquals("<invalid-url>", SafeLogger.redactUrl("ftp://example.com/archive.zip"))
        assertEquals("<invalid-url>", SafeLogger.redactUrl("https://"))
    }

    @Test
    fun `redactPath strips directory hierarchy query strings and embedded URLs`() {
        assertEquals("<empty>", SafeLogger.redactPath(null))
        assertEquals("<empty>", SafeLogger.redactPath("  "))
        assertEquals("<root>", SafeLogger.redactPath("/"))

        assertEquals(
            ".../reader_doc.epub",
            SafeLogger.redactPath("/data/user/0/com.crosssend.app/files/books/reader_doc.epub?token=secret#part")
        )
        assertEquals(
            ".../secret.pdf",
            SafeLogger.redactPath("""C:\Users\reader\Documents\secret.pdf""")
        )
        assertEquals(
            ".../cache",
            SafeLogger.redactPath("/data/user/0/com.crosssend.app/cache/")
        )
        assertEquals(
            "https://example.com/...",
            SafeLogger.redactPath("https://example.com/private/doc.epub?sig=123")
        )
        assertEquals(
            "<redacted-path>",
            SafeLogger.redactPath("/tmp/cache/https://example.com/secret")
        )

        val longName = "a".repeat(80) + ".epub"
        val redactedLong = SafeLogger.redactPath("/storage/emulated/0/Download/$longName")
        assertEquals(".../${"a".repeat(64)}", redactedLong)
    }

    @Test
    fun `sanitizeMessage redacts multiple URLs and private paths and truncates long messages`() {
        val raw = "Sync failed for https://user:pw@sub.example.com/article/slug?auth=1 " +
            "and http://192.168.4.1:8080/upload?x=y while reading " +
            "/data/user/0/com.crosssend.app/files/books/secret.epub and " +
            "/storage/emulated/0/Download/note.txt or /Users/alice/test.txt or /home/bob/doc"

        val sanitized = SafeLogger.sanitizeMessage(raw)
        assertFalse(sanitized.contains("user:pw"))
        assertFalse(sanitized.contains("article/slug"))
        assertFalse(sanitized.contains("auth=1"))
        assertFalse(sanitized.contains("x=y"))
        assertFalse(sanitized.contains("/data/user/0"))
        assertFalse(sanitized.contains("/storage/emulated/0"))
        assertFalse(sanitized.contains("/Users/alice"))
        assertFalse(sanitized.contains("/home/bob"))

        assertTrue(sanitized.contains("https://sub.example.com/..."))
        assertTrue(sanitized.contains("http://192.168.4.1:8080/..."))
        assertTrue(sanitized.contains("<local-path>"))

        assertEquals("", SafeLogger.sanitizeMessage(null))
        assertEquals("", SafeLogger.sanitizeMessage("   "))

        val overlong = "x".repeat(600)
        val truncated = SafeLogger.sanitizeMessage(overlong)
        assertEquals(500, truncated.length)
        assertTrue(truncated.endsWith("..."))
    }

    @Test
    fun `formatLogMessage sanitizes throwable message without exposing raw URLs or paths`() {
        val exception = IllegalStateException(
            "HTTP 500 from https://tok:sec@api.example.com/v1/user?id=99 reading /data/user/0/com.crosssend.app/cache/item"
        )
        val formatted = SafeLogger.formatLogMessage("Upload error for https://example.com/a/b?k=1", exception)

        assertTrue(formatted.contains("Upload error for https://example.com/..."))
        assertTrue(formatted.contains("IllegalStateException: HTTP 500 from https://api.example.com/... reading <local-path>"))
        assertFalse(formatted.contains("tok:sec"))
        assertFalse(formatted.contains("id=99"))
        assertFalse(formatted.contains("/data/user/0"))
    }

    @Test
    fun `d w and e succeed silently under plain JVM unit tests`() {
        val error = RuntimeException("Failed https://secret.example.com/path?token=123 at /Users/dev/file.txt")
        SafeLogger.d("SafeLoggerTest", "Debug https://example.com/a?b=c")
        SafeLogger.w("SafeLoggerTest", "Warn /data/user/0/com.crosssend.app/files/a.epub")
        SafeLogger.w("SafeLoggerTest", "Warn with throwable", error)
        SafeLogger.e("SafeLoggerTest", "Error /storage/emulated/0/Download/b.pdf", error)
    }
}
