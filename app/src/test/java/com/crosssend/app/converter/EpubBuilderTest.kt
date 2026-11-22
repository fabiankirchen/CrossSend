package com.crosssend.app.converter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class EpubBuilderTest {

    private val builder = EpubBuilder()

    private inline fun withTempDir(block: (File) -> Unit) {
        val tempDir = Files.createTempDirectory("epub_builder_test_").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun readZipEntryText(zipFile: ZipFile, entryPath: String): String {
        val entry = zipFile.getEntry(entryPath)
            ?: throw AssertionError("Missing ZIP entry: $entryPath")
        return zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `buildEpub writes compliant mimetype first entry and container xml`() {
        withTempDir { tempDir ->
            val outputFile = File(tempDir, "spec.epub")
            val success = runBlocking {
                builder.buildEpub(
                    title = "EPUB Spec Test",
                    author = "CrossSend Author",
                    contentHtml = "<p>Hello CrossPoint reader.</p>",
                    coverImage = null,
                    inlineImages = emptyList(),
                    outputFile = outputFile,
                    language = "en"
                )
            }

            assertTrue("buildEpub should succeed", success)
            assertTrue("Output EPUB file should exist", outputFile.exists())

            ZipInputStream(FileInputStream(outputFile)).use { zis ->
                val firstEntry = zis.nextEntry
                    ?: throw AssertionError("EPUB archive must have a first entry")
                assertEquals("mimetype", firstEntry.name)
                assertEquals(ZipEntry.STORED, firstEntry.method)
                val extra = firstEntry.extra
                assertTrue("mimetype must have no extra field", extra == null || extra.isEmpty())

                val mimetypeContent = zis.readBytes().toString(Charsets.US_ASCII)
                assertEquals("application/epub+zip", mimetypeContent)
            }

            ZipFile(outputFile).use { zipFile ->
                val mimeZipEntry = zipFile.getEntry("mimetype")
                    ?: throw AssertionError("ZipFile should contain mimetype entry")
                assertEquals(ZipEntry.STORED, mimeZipEntry.method)
                assertEquals(20L, mimeZipEntry.size)
                assertEquals(20L, mimeZipEntry.compressedSize)

                val containerXml = readZipEntryText(zipFile, "META-INF/container.xml")
                assertTrue(
                    "container.xml must point to OEBPS/content.opf",
                    containerXml.contains("full-path=\"OEBPS/content.opf\"")
                )
                assertTrue(
                    "container.xml must declare OPF media-type",
                    containerXml.contains("media-type=\"application/oebps-package+xml\"")
                )
            }
        }
    }

    @Test
    fun `buildEpub rewrites bundled images and strips unbundled or missing images`() {
        withTempDir { tempDir ->
            val validImgFile = File(tempDir, "pic.jpg").apply {
                writeBytes(
                    byteArrayOf(
                        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
                    )
                )
            }
            val missingImgFile = File(tempDir, "does_not_exist.jpg")

            val inlineImg = OptimizedImage(
                originalUrl = "https://cdn.example.com/pic.jpg?utm_source=x",
                localFile = validImgFile,
                relativePath = "images/img_1.jpg",
                width = 400,
                height = 300,
                mediaType = "image/jpeg",
                isCover = false,
                colorCoverFile = null
            )
            val missingImg = OptimizedImage(
                originalUrl = "https://missing.example/broken.jpg",
                localFile = missingImgFile,
                relativePath = "images/img_missing.jpg",
                width = 200,
                height = 100,
                mediaType = "image/jpeg",
                isCover = false,
                colorCoverFile = null
            )

            val html = """
                <p><img src="https://cdn.example.com/pic.jpg?utm_source=x" srcset="big.jpg 2x" data-src="lazy.jpg" alt="Photo"></p>
                <p><img src="https://tracker.evil.example/pixel.gif" alt="Pixel"></p>
                <p><img src="https://missing.example/broken.jpg" alt="Missing"></p>
            """.trimIndent()

            val outputFile = File(tempDir, "images.epub")
            val success = runBlocking {
                builder.buildEpub(
                    title = "Image Rewriting Test",
                    author = "Tester",
                    contentHtml = html,
                    coverImage = null,
                    inlineImages = listOf(inlineImg, missingImg),
                    outputFile = outputFile,
                    language = "en"
                )
            }

            assertTrue("buildEpub should succeed", success)

            ZipFile(outputFile).use { zipFile ->
                val chapterXhtml = readZipEntryText(zipFile, "OEBPS/chapter_1.xhtml")
                assertTrue("Valid image src must be rewritten", chapterXhtml.contains("src=\"images/img_1.jpg\""))
                assertTrue("Valid image width must be added", chapterXhtml.contains("width=\"400\""))
                assertTrue("Valid image height must be added", chapterXhtml.contains("height=\"300\""))
                assertTrue("Valid image alt must be preserved", chapterXhtml.contains("alt=\"Photo\""))

                assertFalse("srcset must be stripped", chapterXhtml.contains("srcset"))
                assertFalse("data-src must be stripped", chapterXhtml.contains("data-src"))
                assertFalse("Original external image URL must be removed", chapterXhtml.contains("cdn.example.com"))
                assertFalse("Unbundled tracker img must be removed", chapterXhtml.contains("tracker.evil.example"))
                assertFalse("Unbundled tracker pixel alt must be removed", chapterXhtml.contains("alt=\"Pixel\""))
                assertFalse("Missing localFile img URL must be removed", chapterXhtml.contains("missing.example"))
                assertFalse("Missing localFile img alt must be removed", chapterXhtml.contains("alt=\"Missing\""))
                assertFalse("Missing image relativePath must not appear in XHTML", chapterXhtml.contains("images/img_missing.jpg"))

                val contentOpf = readZipEntryText(zipFile, "OEBPS/content.opf")
                assertTrue("OPF manifest must include bundled image", contentOpf.contains("images/img_1.jpg"))
                assertFalse("OPF manifest must exclude missing image", contentOpf.contains("images/img_missing.jpg"))

                assertNotNull("Bundled image entry should exist in ZIP", zipFile.getEntry("OEBPS/images/img_1.jpg"))
                assertNull("Missing image entry must not exist in ZIP", zipFile.getEntry("OEBPS/images/img_missing.jpg"))
            }
        }
    }

    @Test
    fun `buildEpub enforces XHTML 1_1 compatibility sanitization and language`() {
        withTempDir { tempDir ->
            val outputFile = File(tempDir, "sanitize.epub")
            val html = """<p><mark>highlighted</mark> and <a href="javascript:alert(1)" onclick="bad()">bad</a></p><script>alert(2)</script>"""

            val success = runBlocking {
                builder.buildEpub(
                    title = "Sanitization Test",
                    author = "Auteur",
                    contentHtml = html,
                    coverImage = null,
                    inlineImages = emptyList(),
                    outputFile = outputFile,
                    language = "fr-FR"
                )
            }

            assertTrue("buildEpub should succeed", success)

            ZipFile(outputFile).use { zipFile ->
                val chapterXhtml = readZipEntryText(zipFile, "OEBPS/chapter_1.xhtml")
                assertTrue("XHTML must declare xml:lang=\"fr\"", chapterXhtml.contains("xml:lang=\"fr\""))
                assertTrue("XHTML must convert <mark> to <strong>", chapterXhtml.contains("<strong>highlighted</strong>"))
                assertFalse("XHTML must not contain <mark>", chapterXhtml.contains("<mark"))
                assertFalse("XHTML must omit <script>", chapterXhtml.contains("<script"))
                assertFalse("XHTML must omit script body", chapterXhtml.contains("alert(2)"))
                assertFalse("XHTML must omit onclick attribute", chapterXhtml.contains("onclick"))
                assertFalse("XHTML must omit javascript: URI", chapterXhtml.contains("javascript:"))

                val contentOpf = readZipEntryText(zipFile, "OEBPS/content.opf")
                assertTrue("OPF must declare <dc:language>fr</dc:language>", contentOpf.contains("<dc:language>fr</dc:language>"))
            }
        }
    }

    @Test
    fun `buildEpub applies custom EpubStylingOptions to style css`() {
        withTempDir { tempDir ->
            val outputFile = File(tempDir, "styled.epub")
            val styling = EpubStylingOptions(
                fontScale = 1.2f,
                lineHeight = 1.6f,
                marginEm = 0.75f
            )

            val success = runBlocking {
                builder.buildEpub(
                    title = "Styling Test",
                    author = "Designer",
                    contentHtml = "<p>Styled E-Ink paragraph.</p>",
                    coverImage = null,
                    inlineImages = emptyList(),
                    outputFile = outputFile,
                    language = "en",
                    styling = styling
                )
            }

            assertTrue("buildEpub should succeed", success)

            ZipFile(outputFile).use { zipFile ->
                val styleCss = readZipEntryText(zipFile, "OEBPS/style.css")
                assertTrue("style.css must include font-size: 120%", styleCss.contains("font-size: 120%"))
                assertTrue("style.css must include line-height: 1.6", styleCss.contains("line-height: 1.6"))
                assertTrue("style.css must include margin: 0 0.75em", styleCss.contains("margin: 0 0.75em"))
            }
        }
    }

    @Test
    fun `buildEpub toc ncx supports more than 12 chapters`() {
        withTempDir { tempDir ->
            val outputFile = File(tempDir, "longread.epub")
            val paragraphFiller = "CrossPoint e-reader pagination test sentence with steady text payload. ".repeat(14)
            val bodyBuilder = StringBuilder()
            val sectionCount = 16
            for (i in 1..sectionCount) {
                bodyBuilder.append("<h2>Section Heading ").append(i).append("</h2>\n")
                for (p in 1..48) {
                    bodyBuilder.append("<p>Item ").append(i).append("-").append(p).append(": ")
                        .append(paragraphFiller).append("</p>\n")
                }
            }

            val success = runBlocking {
                builder.buildEpub(
                    title = "Long Read Anthology",
                    author = "Author",
                    contentHtml = bodyBuilder.toString(),
                    coverImage = null,
                    inlineImages = emptyList(),
                    outputFile = outputFile,
                    language = "en"
                )
            }

            assertTrue("buildEpub should succeed", success)

            ZipFile(outputFile).use { zipFile ->
                val tocNcx = readZipEntryText(zipFile, "OEBPS/toc.ncx")
                val navPointCount = Regex("<navPoint\\b").findAll(tocNcx).count()
                assertTrue(
                    "toc.ncx must contain > 12 navPoint entries (found $navPointCount)",
                    navPointCount > 12
                )
                assertEquals("Expected one navPoint per split chapter", sectionCount, navPointCount)
                assertTrue(
                    "toc.ncx should include later chapter headings",
                    tocNcx.contains("Section Heading 15")
                )
                assertNotNull("chapter_13.xhtml should exist in ZIP", zipFile.getEntry("OEBPS/chapter_13.xhtml"))
            }
        }
    }
}
