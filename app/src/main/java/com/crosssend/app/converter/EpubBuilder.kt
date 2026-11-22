package com.crosssend.app.converter

import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ChapterData(
    val title: String,
    val html: String
)

data class EpubStylingOptions(
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.45f,
    val marginEm: Float = 0.0f
)

class EpubBuilder {

    companion object {
        // DOS ZIP epoch starts in 1980; timestamps before 1980 cause OpenJDK ZipOutputStream
        // to emit an extended timestamp extra field on STORED mimetype entries, breaking EPUB OCF.
        private const val DETERMINISTIC_ZIP_TIME_MS = 1_577_880_000_000L
    }

    private val isDe: Boolean
        get() = Locale.getDefault().language == "de"

    private fun resolveLanguageCode(requestedLanguage: String?): String {
        val normalized = requestedLanguage
            ?.trim()
            ?.split('-', '_')
            ?.firstOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }
        return normalized ?: if (isDe) "de" else "en"
    }

    fun sanitizeTextForEpub(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace("&nbsp;", " ")
            .replace("\u00A0", " ")
            .trim()
    }

    fun sanitizeTitleForEpub(rawTitle: String?): String {
        val defaultDocTitle = if (isDe) "Dokument" else "Document"
        if (rawTitle.isNullOrBlank()) return defaultDocTitle
        var t = sanitizeTextForEpub(rawTitle)
        if (t.length > 100 || t.split("\\s+".toRegex()).size > 15) {
            val firstSentence = t.split(Regex("(?<=[.!?])\\s+|\\n+")).firstOrNull { it.isNotBlank() } ?: t
            t = if (firstSentence.length <= 100 && firstSentence.split("\\s+".toRegex()).size <= 15) {
                firstSentence.trimEnd('.', ' ', ':', '-')
            } else {
                t.take(75).substringBeforeLast(" ", t.take(75)).trimEnd('.', ' ', ':', '-')
            }
        }
        return t.ifBlank { defaultDocTitle }
    }

    suspend fun buildEpub(
        title: String,
        subtitle: String? = null,
        author: String?,
        sourceUrl: String? = null,
        contentHtml: String,
        coverImage: OptimizedImage?,
        inlineImages: List<OptimizedImage>,
        outputFile: File,
        wordCount: Int = 0,
        readingTimeMinutes: Int = 1,
        publishedDate: Date? = null,
        language: String? = null,
        styling: EpubStylingOptions = EpubStylingOptions()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val bookId = UUID.randomUUID().toString()
            val resolvedLanguage = resolveLanguageCode(language)
            val effectiveDate = publishedDate ?: Date()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(effectiveDate)
            val cleanAuthor = author?.let { sanitizeTextForEpub(it) }
            val unknownAuthor = if (isDe) "Unbekannt" else "Unknown"
            val safeAuthor = cleanAuthor?.ifBlank { unknownAuthor } ?: unknownAuthor
            val safeTitle = sanitizeTitleForEpub(title)
            val safeSubtitle = subtitle?.let { sanitizeTextForEpub(it).takeIf { s -> s.isNotBlank() } }
            val sanitizedHtml = sanitizeTextForEpub(contentHtml)

            val validCoverImage = coverImage?.takeIf { it.localFile.exists() }
            val allImages = mutableListOf<OptimizedImage>()
            validCoverImage?.let { allImages.add(it) }
            allImages.addAll(inlineImages.filter { it.localFile.exists() })

            // 1. Parse DOM once, enforce defense-in-depth HTML sanitization, rewrite matched images to
            // local EPUB relative paths with dimensions, and remove any unbundled external images.
            val normalizedHtml = rewriteAndSanitizeBodyDom(sanitizedHtml, allImages)

            // 2. Parse and chunk content into ~65 KB chapters, splitting at <h2>/<h3> headings when appropriate
            val chapters = chunkContentIntoChapters(
                title = safeTitle,
                subtitle = safeSubtitle,
                author = safeAuthor,
                sourceUrl = sourceUrl,
                bodyHtml = normalizedHtml,
                wordCount = wordCount,
                readingTimeMinutes = readingTimeMinutes,
                publishedDate = publishedDate,
                coverImage = validCoverImage,
                language = resolvedLanguage
            )

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
                // 1. mimetype (first entry, uncompressed STORED, zero extra attributes)
                val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val mimetypeEntry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetypeBytes.size.toLong()
                    compressedSize = mimetypeBytes.size.toLong()
                    crc = CRC32().apply { update(mimetypeBytes) }.value
                    time = DETERMINISTIC_ZIP_TIME_MS
                    extra = null
                }
                zip.putNextEntry(mimetypeEntry)
                zip.write(mimetypeBytes)
                zip.closeEntry()

                // 2. META-INF/container.xml
                zip.putNextEntry(ZipEntry("META-INF/container.xml").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                zip.write(createContainerXml().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 3. OEBPS/style.css (Defensive, ultra-lightweight CSS for lightning-fast rendering)
                zip.putNextEntry(ZipEntry("OEBPS/style.css").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                zip.write(createDefensiveEInkCss(styling).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 4. OEBPS/chapter_*.xhtml (Start reading directly at chapter 1 without slow cover page)
                for ((index, chapter) in chapters.withIndex()) {
                    val chapterNum = index + 1
                    zip.putNextEntry(ZipEntry("OEBPS/chapter_$chapterNum.xhtml").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                    zip.write(chapter.html.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                // 6. OEBPS/toc.ncx (Fast entry with chapter headings for easy navigation)
                zip.putNextEntry(ZipEntry("OEBPS/toc.ncx").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                zip.write(createFastTocNcx(bookId, safeTitle, safeAuthor, chapters).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 7. OEBPS/images
                for (img in allImages) {
                    zip.putNextEntry(ZipEntry("OEBPS/${img.relativePath}").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                    img.localFile.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }

                // 8. OEBPS/content.opf
                zip.putNextEntry(ZipEntry("OEBPS/content.opf").apply { time = DETERMINISTIC_ZIP_TIME_MS })
                zip.write(
                    createContentOpf(
                        bookId = bookId,
                        title = safeTitle,
                        author = safeAuthor,
                        date = dateStr,
                        coverImage = validCoverImage,
                        chapterCount = chapters.size,
                        images = allImages,
                        language = resolvedLanguage
                    ).toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }

            true
        } catch (e: Exception) {
            SafeLogger.e("EpubBuilder", "Failed to build EPUB", e)
            false
        }
    }

    private fun rewriteAndSanitizeBodyDom(sanitizedHtml: String, existingImages: List<OptimizedImage>): String {
        val bodyDoc = Jsoup.parseBodyFragment(sanitizedHtml)
        bodyDoc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)

        bodyDoc.select(
            "script, style, noscript, iframe, object, embed, form, input, button, " +
                "canvas, video, audio, source, track, map, area, applet, base, meta, link"
        ).remove()

        // XHTML 1.1 does not support <mark>; convert to <strong> for strict reader compatibility.
        for (markEl in bodyDoc.select("mark")) {
            markEl.tagName("strong")
        }

        val imageByUrl = HashMap<String, OptimizedImage>(existingImages.size * 2)
        for (img in existingImages) {
            imageByUrl[img.originalUrl] = img
            imageByUrl[img.originalUrl.replace("&amp;", "&")] = img
            imageByUrl[img.relativePath] = img
        }

        for (imgEl in bodyDoc.select("img").toList()) {
            val rawSrc = imgEl.attr("src").trim()
            val matched = imageByUrl[rawSrc] ?: imageByUrl[rawSrc.replace("&amp;", "&")]
            if (matched != null) {
                val altText = imgEl.attr("alt")
                for (attr in imgEl.attributes().asList()) {
                    imgEl.removeAttr(attr.key)
                }
                imgEl.attr("src", matched.relativePath)
                imgEl.attr("width", matched.width.toString())
                imgEl.attr("height", matched.height.toString())
                if (altText.isNotBlank()) {
                    imgEl.attr("alt", altText)
                } else {
                    imgEl.attr("alt", "")
                }
            } else {
                imgEl.remove()
            }
        }

        val uriAttributes = setOf("href", "xlink:href", "formaction", "action", "data", "srcdoc")
        for (el in bodyDoc.body().getAllElements()) {
            if (el == bodyDoc.body()) continue
            val tagLower = el.tagName().lowercase(Locale.ROOT)
            if (tagLower == "img") continue
            val toRemove = mutableListOf<String>()
            for (attr in el.attributes()) {
                val keyLower = attr.key.lowercase(Locale.ROOT)
                if (keyLower == "style" || keyLower == "ping" || keyLower.startsWith("on")) {
                    toRemove.add(attr.key)
                } else if (keyLower in uriAttributes) {
                    if (tagLower != "a" || keyLower != "href" || !EInkImageOptimizer.isSafeHtmlUri(attr.value, allowRelative = true)) {
                        toRemove.add(attr.key)
                    }
                }
            }
            for (k in toRemove) {
                el.removeAttr(k)
            }
        }

        return bodyDoc.body().html()
    }

    private fun chunkContentIntoChapters(
        title: String,
        subtitle: String? = null,
        author: String?,
        sourceUrl: String?,
        bodyHtml: String,
        wordCount: Int,
        readingTimeMinutes: Int,
        publishedDate: Date? = null,
        coverImage: OptimizedImage? = null,
        language: String = if (isDe) "de" else "en"
    ): List<ChapterData> {
        val doc = Jsoup.parseBodyFragment(bodyHtml)
        val elements = doc.body().children()

        val chapters = mutableListOf<ChapterData>()
        var currentChunk = StringBuilder()
        var currentChunkBytes = 0
        var currentChapterTitle = title
        val maxChunkBytes = 65000 // Keep articles in a single spine item for sub-second indexing on CrossPoint
        val headingSplitThreshold = 45000 // Only split at headings for massive longreads / books

        for (el in elements) {
            val tagName = el.tagName().lowercase()
            val isHeading = tagName in listOf("h1", "h2", "h3", "h4", "h5", "h6")

            // Guard against runaway headings that accidentally swallowed paragraphs (e.g. > 120 chars)
            if (isHeading && (el.text().length > 120 || el.text().split("\\s+".toRegex()).size > 15 || el.text().contains("\n"))) {
                val sentences = el.text().split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotBlank() }
                if (sentences.isNotEmpty()) {
                    val headText = sentences.first()
                    val safeHeadText = if (headText.length > 100) headText.take(75).substringBeforeLast(" ", headText.take(75)) else headText
                    val hXml = "<$tagName>${escapeXml(safeHeadText)}</$tagName>"
                    currentChunk.append(hXml).append("\n")
                    currentChunkBytes += hXml.toByteArray().size

                    val remainingSentences = sentences.drop(1)
                    var pGroup = StringBuilder()
                    var sCount = 0
                    for (s in remainingSentences) {
                        if (pGroup.isNotEmpty()) pGroup.append(" ")
                        pGroup.append(s)
                        sCount++
                        if (sCount >= 3 || pGroup.length > 300) {
                            val pXml = "<p>${escapeXml(pGroup.toString())}</p>"
                            currentChunk.append(pXml).append("\n")
                            currentChunkBytes += pXml.toByteArray().size
                            pGroup = StringBuilder()
                            sCount = 0
                        }
                    }
                    if (pGroup.isNotEmpty()) {
                        val pXml = "<p>${escapeXml(pGroup.toString())}</p>"
                        currentChunk.append(pXml).append("\n")
                        currentChunkBytes += pXml.toByteArray().size
                    }
                    continue
                }
            }

            if (isHeading && currentChunkBytes > headingSplitThreshold && currentChunk.isNotEmpty()) {
                val isFirst = chapters.isEmpty()
                val wrapped = wrapInXhtmlDocument(
                    title = title,
                    subtitle = subtitle,
                    author = author,
                    sourceUrl = sourceUrl,
                    wordCount = wordCount,
                    readingTimeMinutes = readingTimeMinutes,
                    isFirstChapter = isFirst,
                    bodyContent = currentChunk.toString(),
                    publishedDate = publishedDate,
                    coverImage = coverImage,
                    language = language
                )
                chapters.add(ChapterData(title = currentChapterTitle, html = wrapped))

                val sectionPrefix = if (isDe) "Abschnitt" else "Section"
                currentChapterTitle = sanitizeTitleForEpub(el.text().trim().ifBlank { "$sectionPrefix ${chapters.size + 1}" })
                currentChunk = StringBuilder()
                currentChunkBytes = 0
            }

            if (tagName == "p" && el.text().length > 1500) {
                val sentences = el.text().split(Regex("(?<=[.!?])\\s+"))
                var subPara = StringBuilder()
                for (sentence in sentences) {
                    if (subPara.length + sentence.length > 1200) {
                        val pXml = toValidXml("<p>${escapeXml(subPara.toString())}</p>")
                        currentChunk.append(pXml).append("\n")
                        currentChunkBytes += pXml.toByteArray().size
                        subPara = StringBuilder(sentence)
                    } else {
                        if (subPara.isNotEmpty()) subPara.append(" ")
                        subPara.append(sentence)
                    }
                }
                if (subPara.isNotEmpty()) {
                    val pXml = toValidXml("<p>${escapeXml(subPara.toString())}</p>")
                    currentChunk.append(pXml).append("\n")
                    currentChunkBytes += pXml.toByteArray().size
                }
            } else {
                val elXml = toValidXml(el.outerHtml())
                val elBytes = elXml.toByteArray().size

                if (currentChunkBytes + elBytes > maxChunkBytes && currentChunk.isNotEmpty()) {
                    val isFirst = chapters.isEmpty()
                    val wrapped = wrapInXhtmlDocument(
                        title = title,
                        subtitle = subtitle,
                        author = author,
                        sourceUrl = sourceUrl,
                        wordCount = wordCount,
                        readingTimeMinutes = readingTimeMinutes,
                        isFirstChapter = isFirst,
                        bodyContent = currentChunk.toString(),
                        publishedDate = publishedDate,
                        coverImage = coverImage,
                        language = language
                    )
                    chapters.add(ChapterData(title = currentChapterTitle, html = wrapped))

                    val sectionPrefix = if (isDe) "Abschnitt" else "Section"
                    currentChapterTitle = "$sectionPrefix ${chapters.size + 1}"
                    currentChunk = StringBuilder()
                    currentChunkBytes = 0
                }
                currentChunk.append(elXml).append("\n")
                currentChunkBytes += elBytes
            }
        }

        if (currentChunk.isNotEmpty() || chapters.isEmpty()) {
            val isFirst = chapters.isEmpty()
            val wrapped = wrapInXhtmlDocument(
                title = title,
                subtitle = subtitle,
                author = author,
                sourceUrl = sourceUrl,
                wordCount = wordCount,
                readingTimeMinutes = readingTimeMinutes,
                isFirstChapter = isFirst,
                bodyContent = currentChunk.toString(),
                publishedDate = publishedDate,
                coverImage = coverImage,
                language = language
            )
            chapters.add(ChapterData(title = currentChapterTitle, html = wrapped))
        }

        return chapters
    }

    private fun toValidXml(htmlSnippet: String): String {
        val snippetDoc = Jsoup.parseBodyFragment(htmlSnippet)
        snippetDoc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
        return snippetDoc.body().html()
    }

    private fun extractDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val host = java.net.URI(url).host ?: return null
            host.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    private fun wrapInXhtmlDocument(
        title: String,
        subtitle: String? = null,
        author: String?,
        sourceUrl: String?,
        wordCount: Int,
        readingTimeMinutes: Int,
        isFirstChapter: Boolean,
        bodyContent: String,
        publishedDate: Date? = null,
        coverImage: OptimizedImage? = null,
        language: String = if (isDe) "de" else "en"
    ): String {
        // Extract clean domain or publication name if available
        val cleanDomain = extractDomain(sourceUrl)
        val validAuthor = author?.trim()?.takeIf {
            it.isNotBlank() &&
            !it.equals("CrossSend", ignoreCase = true) &&
            !it.equals("Unbekannt", ignoreCase = true) &&
            !it.equals("Unknown", ignoreCase = true)
        }

        val dateText = publishedDate?.let {
            SimpleDateFormat(if (isDe) "dd.MM.yyyy" else "yyyy-MM-dd", if (isDe) Locale.GERMANY else Locale.US).format(it)
        }

        val parts = mutableListOf<String>()
        if (validAuthor != null) parts.add(validAuthor)
        if (cleanDomain != null && (validAuthor == null || !validAuthor.contains(cleanDomain, ignoreCase = true))) {
            parts.add(cleanDomain)
        }
        if (dateText != null) parts.add(dateText)

        val bylineText = if (parts.isNotEmpty()) parts.joinToString(" · ") else null

        val cleanTitle = sanitizeTitleForEpub(sanitizeTextForEpub(title))
        val cleanSubtitle = subtitle?.let { sanitizeTextForEpub(it).takeIf { s -> s.isNotBlank() } }

        val header = if (isFirstChapter) {
            val bylineHtml = if (bylineText != null) {
                "<p class=\"byline\">${escapeXml(bylineText)}</p>"
            } else ""
            val subtitleHtml = if (!cleanSubtitle.isNullOrBlank()) {
                "<p class=\"subtitle\"><em>${escapeXml(cleanSubtitle)}</em></p>"
            } else ""
            val leadImageHtml = if (coverImage != null && !bodyContent.contains(coverImage.relativePath)) {
                "<div class=\"lead-image-wrap\"><img src=\"${coverImage.relativePath}\" width=\"${coverImage.width}\" height=\"${coverImage.height}\" class=\"lead-image\" alt=\"\" /></div>"
            } else ""
            """
            <h1 class="article-title">${escapeXml(cleanTitle)}</h1>
            $subtitleHtml
            $bylineHtml
            $leadImageHtml
            """.trimIndent()
        } else ""

        // Strip any duplicate h1-h6 matching the title from the beginning of bodyContent (never strip <p> paragraphs!)
        var cleanBodyContent = bodyContent.trim()
        val leadingHeadingRegex = Regex("^<(h[1-6])[^>]*>(.*?)</\\1>\\s*", RegexOption.IGNORE_CASE)
        var headingMatch = leadingHeadingRegex.find(cleanBodyContent)
        while (headingMatch != null) {
            val blockText = Jsoup.parseBodyFragment(headingMatch.groupValues[2]).text().trim()
            val isDuplicate = blockText.equals(cleanTitle, ignoreCase = true) ||
                (blockText.length in 5..120 && (cleanTitle.startsWith(blockText, ignoreCase = true) || blockText.startsWith(cleanTitle, ignoreCase = true))) ||
                (cleanDomain != null && blockText.startsWith(cleanTitle, ignoreCase = true) && blockText.contains(cleanDomain, ignoreCase = true)) ||
                (!cleanSubtitle.isNullOrBlank() && blockText.length in 5..120 && (blockText.equals(cleanSubtitle, ignoreCase = true) || cleanSubtitle.startsWith(blockText, ignoreCase = true)))
            if (isDuplicate) {
                cleanBodyContent = cleanBodyContent.substring(headingMatch.range.last + 1).trim()
                headingMatch = leadingHeadingRegex.find(cleanBodyContent)
            } else {
                break
            }
        }

        val langCode = language
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="$langCode">
<head>
    <title>${escapeXml(cleanTitle)}</title>
    <link rel="stylesheet" type="text/css" href="style.css" />
</head>
<body>
    $header
    <div class="content">
        $cleanBodyContent
    </div>
</body>
</html>"""
    }

    private fun createContainerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""

    private fun formatCssDecimal(value: Float): String {
        val formatted = String.format(Locale.ROOT, "%.2f", value)
        return formatted.trimEnd('0').trimEnd('.')
    }

    /**
     * Defensive CSS: No justify, no complex margins, no font overriding.
     * Lets CrossPoint render at maximum hardware speed!
     */
    private fun createDefensiveEInkCss(styling: EpubStylingOptions = EpubStylingOptions()): String {
        val fontScale = styling.fontScale.coerceIn(0.85f, 1.35f)
        val lineHeightStr = formatCssDecimal(styling.lineHeight.coerceIn(1.25f, 1.8f))
        val marginEm = styling.marginEm.coerceIn(0f, 1.5f)
        val marginRule = if (marginEm > 0.01f) "0 ${formatCssDecimal(marginEm)}em" else "0"
        val fontSizeRule = if (kotlin.math.abs(fontScale - 1.0f) > 0.01f) {
            "\n    font-size: ${(fontScale * 100f).toInt()}%;"
        } else {
            ""
        }
        return """
body {
    margin: $marginRule;
    padding: 0;$fontSizeRule
    line-height: $lineHeightStr;
    color: #000000;
}
a {
    color: #000000;
}
h1, h2, h3, h4 {
    font-weight: bold;
    line-height: 1.25;
}
h1.article-title {
    font-size: 1.35em;
    border-bottom: 1px solid #000;
    padding-bottom: 0.25em;
    margin-bottom: 0.25em;
}
.subtitle {
    font-size: 0.95em;
    color: #000000;
    font-style: italic;
    margin: 0.25em 0 0.5em 0;
    line-height: 1.35;
}
.byline {
    font-size: 0.82em;
    color: #000000;
    margin: 0.1em 0 0.8em 0;
    line-height: 1.2;
}
.lead-image-wrap {
    margin: 0.8em auto 1.0em auto;
    text-align: center;
}
.lead-image-wrap img, img.lead-image {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 0 auto;
}
h2 {
    font-size: 1.2em;
    margin: 1.0em 0 0.35em 0;
    border-bottom: 1px dotted #888;
    padding-bottom: 0.15em;
}
h3 {
    font-size: 1.08em;
    margin: 0.85em 0 0.3em 0;
}
p {
    margin: 0.45em 0;
    line-height: $lineHeightStr;
    text-indent: 0;
}
blockquote {
    margin: 0.8em 0.8em;
    padding: 0.3em 0 0.3em 0.8em;
    border-left: 3px solid #000;
    font-style: italic;
}
.pullquote, blockquote.pullquote, aside.pullquote {
    margin: 1.0em 0.8em;
    padding: 0.4em 0 0.4em 0.8em;
    border-left: 4px solid #000;
    font-size: 1.08em;
    font-weight: bold;
    font-style: italic;
    line-height: 1.35;
}
figure {
    margin: 0.8em 0;
    padding: 0;
    text-align: center;
}
figcaption {
    font-size: 0.85em;
    color: #000000;
    font-style: italic;
    margin-top: 0.3em;
    text-align: center;
}
img {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 0.6em auto;
}
strong, b {
    font-weight: bold;
}
em, i {
    font-style: italic;
}
pre {
    background-color: #f2f2f2;
    padding: 0.4em;
    font-size: 0.85em;
    white-space: pre-wrap;
    word-wrap: break-word;
    margin: 0.6em 0;
    border: 1px solid #ccc;
}
code {
    font-family: monospace;
    font-size: 0.9em;
}
""".trimIndent()
    }

    private fun createFastTocNcx(
        bookId: String,
        title: String,
        author: String,
        chapters: List<ChapterData>
    ): String {
        val maxNavPoints = 256
        val safeBookTitle = sanitizeTitleForEpub(title)
        val sectionPrefix = if (isDe) "Abschnitt" else "Section"
        val navPoints = chapters.take(maxNavPoints).mapIndexed { idx, ch ->
            val playOrder = idx + 1
            val chTitle = sanitizeTitleForEpub(ch.title.ifBlank { "$sectionPrefix $playOrder" })
            """
        <navPoint id="navpoint-$playOrder" playOrder="$playOrder">
            <navLabel><text>${escapeXml(chTitle)}</text></navLabel>
            <content src="chapter_$playOrder.xhtml"/>
        </navPoint>"""
        }.joinToString("\n")

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="$bookId"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle><text>${escapeXml(safeBookTitle)}</text></docTitle>
    <docAuthor><text>${escapeXml(author)}</text></docAuthor>
    <navMap>
$navPoints
    </navMap>
</ncx>"""
    }

    private fun createContentOpf(
        bookId: String,
        title: String,
        author: String,
        date: String,
        coverImage: OptimizedImage?,
        chapterCount: Int,
        images: List<OptimizedImage>,
        language: String = if (isDe) "de" else "en"
    ): String {
        val manifestItems = StringBuilder()
        val spineItems = StringBuilder()

        val coverMeta = if (coverImage != null) {
            """        <meta name="cover" content="cover-image"/>"""
        } else ""

        if (coverImage != null) {
            manifestItems.append("""        <item id="cover-image" href="${coverImage.relativePath}" media-type="${coverImage.mediaType}"/>""").append("\n")
        }

        for (i in 1..chapterCount) {
            manifestItems.append("""        <item id="ch_$i" href="chapter_$i.xhtml" media-type="application/xhtml+xml"/>""").append("\n")
            spineItems.append("""        <itemref idref="ch_$i"/>""").append("\n")
        }

        for ((index, img) in images.withIndex()) {
            if (img.isCover) continue // Already added as cover-image
            manifestItems.append("""        <item id="img_$index" href="${img.relativePath}" media-type="${img.mediaType}"/>""").append("\n")
        }

        val langCode = language
        return """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>${escapeXml(title)}</dc:title>
        <dc:creator opf:role="aut">${escapeXml(author)}</dc:creator>
        <dc:language>$langCode</dc:language>
        <dc:identifier id="BookId">$bookId</dc:identifier>
        <dc:date>$date</dc:date>
$coverMeta    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="style" href="style.css" media-type="text/css"/>
$manifestItems    </manifest>
    <spine toc="ncx">
$spineItems    </spine>
</package>"""
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
