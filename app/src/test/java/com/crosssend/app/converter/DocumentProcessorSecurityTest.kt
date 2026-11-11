package com.crosssend.app.converter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class DocumentProcessorSecurityTest {

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9äöüÄÖÜß_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "Dokument" }
            .take(50)
    }

    private fun sanitizeFileNameWithExtension(rawName: String, fallbackExt: String = ""): String {
        val cleanName = rawName.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = cleanName.lastIndexOf('.')
        val (baseName, ext) = if (dotIndex >= 0) {
            cleanName.substring(0, dotIndex) to cleanName.substring(dotIndex + 1)
        } else {
            cleanName to fallbackExt
        }
        val safeBase = sanitizeFilename(baseName)
        val safeExt = ext.replace(Regex("[^a-zA-Z0-9]"), "").take(10)
        return if (safeExt.isNotBlank()) "$safeBase.$safeExt" else safeBase
    }

    private fun ensureInsideDirectory(file: File, dir: File): File {
        val dirCanonical = dir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (!fileCanonical.startsWith(dirCanonical + File.separator)) {
            throw SecurityException("Ungültiger Dateipfad")
        }
        return file
    }

    @Test
    fun `sanitizeFileNameWithExtension strips Unix and Windows path traversal`() {
        val unixTraversal = "../../databases/crosssend_db.epub"
        assertEquals("crosssend_db.epub", sanitizeFileNameWithExtension(unixTraversal))

        val winTraversal = "..\\..\\shared_prefs\\crosspoint_prefs.xml"
        assertEquals("crosspoint_prefs.xml", sanitizeFileNameWithExtension(winTraversal))

        val dotDotOnly = "../..//.epub"
        assertEquals("Dokument.epub", sanitizeFileNameWithExtension(dotDotOnly))
    }

    @Test(expected = SecurityException::class)
    fun `ensureInsideDirectory rejects file outside target directory`() {
        val tempRoot = Files.createTempDirectory("crosssend_test").toFile()
        try {
            val libraryDir = File(tempRoot, "library").apply { mkdirs() }
            val maliciousFile = File(libraryDir, "../databases/crosssend_db")
            ensureInsideDirectory(maliciousFile, libraryDir)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `ensureInsideDirectory accepts valid file inside target directory`() {
        val tempRoot = Files.createTempDirectory("crosssend_test").toFile()
        try {
            val libraryDir = File(tempRoot, "library").apply { mkdirs() }
            val safeFile = File(libraryDir, sanitizeFileNameWithExtension("../../The_Undertow.epub"))
            val verified = ensureInsideDirectory(safeFile, libraryDir)
            assertEquals("The_Undertow.epub", verified.name)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `browser screenshot hash detection matches 32-digit numeric preview filenames`() {
        val screenshotName = "17906035008253437531390400816114.png"
        val baseTitle = screenshotName.substringBeforeLast('.')
        val isScreenshotHash = baseTitle.matches(Regex("^\\d{16,}$"))
        assertTrue(isScreenshotHash)

        val validBookName = "1984_George_Orwell.epub"
        assertFalse(validBookName.substringBeforeLast('.').matches(Regex("^\\d{16,}$")))
    }

    @Test
    fun `isSafeExternalImageUrl blocks loopback private and link-local SSRF targets`() {
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://127.0.0.1/admin"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://localhost:8080/secret.png"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://192.168.4.1/api/status"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://10.0.0.1/internal.jpg"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("file:///etc/passwd"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("http://crosspoint.local/cover.jpg"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("javascript:alert(1)"))
        assertFalse(EInkImageOptimizer.isSafeExternalImageUrl("about:blank"))
        assertTrue(EInkImageOptimizer.isSafeExternalImageUrl("https://example.com/article.html"))
        assertTrue(EInkImageOptimizer.isSafeExternalUrl("https://news.ycombinator.com/item?id=1"))
    }

    @Test
    fun `readBoundedBytes reads exact and under-limit streams and rejects oversized streams`() {
        val payload = ByteArray(1024) { (it % 251).toByte() }

        val underLimit = DocumentProcessor.readBoundedBytes(ByteArrayInputStream(payload), 2048)
        assertArrayEquals(payload, underLimit)

        val exactLimit = DocumentProcessor.readBoundedBytes(ByteArrayInputStream(payload), 1024)
        assertArrayEquals(payload, exactLimit)

        val overLimit = DocumentProcessor.readBoundedBytes(ByteArrayInputStream(payload), 1023)
        assertNull(overLimit)

        val negativeLimit = DocumentProcessor.readBoundedBytes(ByteArrayInputStream(payload), -1)
        assertNull(negativeLimit)

        val emptyWithinZero = DocumentProcessor.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), 0)
        assertArrayEquals(ByteArray(0), emptyWithinZero)
    }

    @Test
    fun `copyBounded copies up to limit and returns -1 when limit is exceeded`() {
        val payload = ByteArray(20000) { (it % 127).toByte() }

        val okOut = ByteArrayOutputStream()
        val copied = DocumentProcessor.copyBounded(ByteArrayInputStream(payload), okOut, 20000L)
        assertEquals(20000L, copied)
        assertArrayEquals(payload, okOut.toByteArray())

        val failOut = ByteArrayOutputStream()
        val rejected = DocumentProcessor.copyBounded(ByteArrayInputStream(payload), failOut, 19999L)
        assertEquals(-1L, rejected)
        assertTrue(failOut.size() < payload.size)

        val negativeOut = ByteArrayOutputStream()
        assertEquals(-1L, DocumentProcessor.copyBounded(ByteArrayInputStream(payload), negativeOut, -1L))
    }
}
