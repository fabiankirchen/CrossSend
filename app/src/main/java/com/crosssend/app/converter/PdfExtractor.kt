package com.crosssend.app.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.crosssend.app.R
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

data class ExtractedPdf(
    val title: String,
    val author: String? = null,
    val pageCount: Int,
    val contentHtml: String,
    val wordCount: Int,
    val readingTimeMinutes: Int
)

class PdfExtractor(private val context: Context) {
    private val tag = "PdfExtractor"

    suspend fun extractFromUri(uri: Uri, defaultTitle: String): ExtractedPdf = withContext(Dispatchers.IO) {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.proc_error_pdf_open))
        inputStream.use { stream ->
            extractFromStream(stream, defaultTitle)
        }
    }

    suspend fun extractFromFile(file: File, defaultTitle: String): ExtractedPdf = withContext(Dispatchers.IO) {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
        file.inputStream().use { stream ->
            extractFromStream(stream, defaultTitle)
        }
    }

    /**
     * Renders the first page of a PDF as a Bitmap downsampled for E-Ink cover display (width 480px).
     */
    fun renderFirstPageAsBitmap(uri: Uri): Bitmap? {
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            }

            if (pfd == null) {
                tempFile = File.createTempFile("pdf_cover_", ".pdf", context.cacheDir)
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        DocumentProcessor.copyBounded(input, output, DocumentProcessor.MAX_FILE_COPY_BYTES)
                    }
                } ?: -1L
                if (copied < 0L) return null
                pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null

            page = renderer.openPage(0)
            val origWidth = page.width
            val origHeight = page.height
            if (origWidth <= 0 || origHeight <= 0) return null

            // Target dimensions: E-Ink screen width 480px, maintaining aspect ratio
            val targetWidth = 480
            val targetHeight = ((origHeight.toFloat() / origWidth.toFloat()) * targetWidth).roundToInt().coerceIn(200, 1000)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: Exception) {
            Log.w(tag, "Could not render first page of PDF: ${e.localizedMessage}")
            return null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    fun renderFirstPageAsBitmap(file: File): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null

            page = renderer.openPage(0)
            val origWidth = page.width
            val origHeight = page.height
            if (origWidth <= 0 || origHeight <= 0) return null

            val targetWidth = 480
            val targetHeight = ((origHeight.toFloat() / origWidth.toFloat()) * targetWidth).roundToInt().coerceIn(200, 1000)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: Exception) {
            Log.w(tag, "Could not render first page of PDF file: ${e.localizedMessage}")
            return null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun extractFromStream(stream: InputStream, fallbackTitle: String): ExtractedPdf {
        val document = PDDocument.load(stream)
        document.use { doc ->
            val pageCount = doc.numberOfPages
            val info = doc.documentInformation

            val stripper = StructuredTextStripper().apply {
                sortByPosition = true
                startPage = 1
                endPage = minOf(pageCount, 1500)
            }

            // Extract all lines with position and font metadata
            stripper.getText(doc)
            val allLines = stripper.collectedLines

            if (allLines.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.proc_error_pdf_no_text_ocr))
            }

            // 1. Calculate typography metrics
            val longLines = allLines.filter { it.text.length >= 20 }
            val bodyFontSize = if (longLines.isNotEmpty()) {
                val sizeCounts = longLines.groupingBy { (it.fontSize * 10).roundToInt() / 10f }.eachCount()
                sizeCounts.maxByOrNull { it.value }?.key ?: 10f
            } else {
                10f
            }

            val bodyFontName = if (longLines.isNotEmpty()) {
                val fontCounts = longLines.groupingBy { it.fontName }.eachCount()
                fontCounts.maxByOrNull { it.value }?.key ?: ""
            } else {
                ""
            }

            // Calculate median line spacing
            val spacings = mutableListOf<Float>()
            for (i in 0 until allLines.size - 1) {
                val cur = allLines[i]
                val next = allLines[i + 1]
                if (cur.page == next.page && next.y > cur.y) {
                    val delta = next.y - cur.y
                    if (delta in 6f..40f) {
                        spacings.add(delta)
                    }
                }
            }
            spacings.sort()
            val normalLineSpacing = if (spacings.isNotEmpty()) spacings[spacings.size / 2] else (bodyFontSize * 1.35f)

            // 2. Detect title & author from metadata or page 1
            val metaTitle = info?.title?.trim()?.takeIf { it.isNotBlank() && !it.endsWith(".pdf", ignoreCase = true) }
            val metaAuthor = info?.author?.trim()?.takeIf { it.isNotBlank() }

            val (detectedTitle, detectedAuthor, hasCoverPage) = detectTitleAndAuthor(
                lines = allLines,
                bodyFontSize = bodyFontSize,
                fallbackTitle = metaTitle ?: fallbackTitle,
                metaAuthor = metaAuthor
            )

            // 3. Filter running headers and footers
            val filteredLines = filterHeadersAndFooters(
                lines = allLines,
                skipPage1 = hasCoverPage
            )

            // 4. Dehyphenate across lines
            dehyphenateLines(filteredLines)

            // 5. Build structured HTML
            val contentHtml = buildHtmlContent(
                lines = filteredLines,
                bodyFontSize = bodyFontSize,
                bodyFontName = bodyFontName,
                normalLineSpacing = normalLineSpacing
            )

            val totalWordCount = contentHtml.replace(Regex("<[^>]+>"), " ")
                .split(Regex("\\s+"))
                .count { it.isNotBlank() }

            if (totalWordCount < 20) {
                throw IllegalStateException(context.getString(R.string.proc_error_pdf_no_text))
            }

            val readingTimeMinutes = maxOf(1, (totalWordCount / 210.0).roundToInt())

            Log.i(tag, "Successfully extracted $totalWordCount words across $pageCount pages from '$detectedTitle'")

            return ExtractedPdf(
                title = detectedTitle,
                author = detectedAuthor,
                pageCount = pageCount,
                contentHtml = contentHtml,
                wordCount = totalWordCount,
                readingTimeMinutes = readingTimeMinutes
            )
        }
    }

    private data class RawPdfLine(
        var text: String,
        val page: Int,
        val x: Float,
        val y: Float,
        val pageWidth: Float,
        val pageHeight: Float,
        val fontSize: Float,
        val isBold: Boolean,
        val isItalic: Boolean,
        val fontName: String
    )

    private class StructuredTextStripper : PDFTextStripper() {
        val collectedLines = mutableListOf<RawPdfLine>()
        private var currentPage = 0

        init {
            sortByPosition = true
        }

        override fun startPage(page: PDPage) {
            super.startPage(page)
            currentPage = currentPageNo
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || textPositions.isEmpty()) return

            val first = textPositions.first()
            val font = first.font
            val fontName = font?.name ?: ""
            val fnLower = fontName.lowercase()
            val isBold = fnLower.contains("bold") || fnLower.contains("black") ||
                    fnLower.contains("heavy") || fnLower.contains("semibold")
            val isItalic = fnLower.contains("italic") || fnLower.contains("oblique")

            val avgSize = textPositions.map { it.fontSizeInPt }.average().toFloat()

            collectedLines.add(
                RawPdfLine(
                    text = trimmed,
                    page = currentPage,
                    x = first.xDirAdj,
                    y = first.yDirAdj,
                    pageWidth = first.pageWidth,
                    pageHeight = first.pageHeight,
                    fontSize = avgSize,
                    isBold = isBold,
                    isItalic = isItalic,
                    fontName = fontName
                )
            )
        }
    }

    private data class TitleAuthorResult(
        val title: String,
        val author: String?,
        val hasCoverPage: Boolean
    )

    private fun detectTitleAndAuthor(
        lines: List<RawPdfLine>,
        bodyFontSize: Float,
        fallbackTitle: String,
        metaAuthor: String?
    ): TitleAuthorResult {
        val page1Lines = lines.filter { it.page == 1 }
        if (page1Lines.isEmpty()) {
            return TitleAuthorResult(fallbackTitle, metaAuthor, false)
        }

        val isCoverPage = page1Lines.size <= 25
        val maxFontSize = page1Lines.maxOfOrNull { it.fontSize } ?: bodyFontSize

        var title = fallbackTitle
        var author = metaAuthor

        if (maxFontSize >= bodyFontSize * 1.3f) {
            val titleLines = page1Lines.filter { it.fontSize >= maxFontSize * 0.72f && it.text.length in 3..140 }
            if (titleLines.isNotEmpty()) {
                val candidateTitle = titleLines.joinToString(" ") { it.text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (candidateTitle.length >= 4 && candidateTitle.any { it.isLetter() }) {
                    title = candidateTitle
                }
            }
        }

        if (author == null) {
            for (line in page1Lines) {
                val t = line.text
                val authorPrefixes = listOf("whitepaper by ", "written by ", "authored by ", "by ", "von ", "autor: ", "author: ")
                val matched = authorPrefixes.firstOrNull { t.startsWith(it, ignoreCase = true) }
                if (matched != null) {
                    author = t.substring(matched.length).trim()
                    break
                }
            }
        }

        return TitleAuthorResult(title, author, isCoverPage)
    }

    private fun filterHeadersAndFooters(
        lines: List<RawPdfLine>,
        skipPage1: Boolean
    ): MutableList<RawPdfLine> {
        val topBottomNormalizedTexts = mutableListOf<String>()
        for (l in lines) {
            val isMargin = l.y < l.pageHeight * 0.09f || l.y > l.pageHeight * 0.91f
            if (isMargin) {
                val norm = l.text.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
                if (norm.length in 3..60) {
                    topBottomNormalizedTexts.add(norm)
                }
            }
        }
        val freqMap = topBottomNormalizedTexts.groupingBy { it }.eachCount()

        val result = mutableListOf<RawPdfLine>()
        for (l in lines) {
            if (skipPage1 && l.page == 1) continue

            val isMargin = l.y < l.pageHeight * 0.09f || l.y > l.pageHeight * 0.91f

            if (isPageNumber(l.text)) continue

            if (isMargin) {
                val norm = l.text.lowercase().replace(Regex("[^a-z0-9äöüß]"), "")
                if (norm.isNotBlank() && (freqMap[norm] ?: 0) >= 2) {
                    continue
                }
                if (l.text.matches(Regex("(?i)^(https?://|www\\.).*"))) {
                    continue
                }
            }
            result.add(l)
        }
        return result
    }

    private fun isPageNumber(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.matches(Regex("^(?:-\\s*)?\\d+(?:\\s*-)?$")) ||
                trimmed.matches(Regex("(?i)^(?:Seite|Page)\\s+\\d+(?:\\s+(?:von|of)\\s+\\d+)?$")) ||
                trimmed.matches(Regex("^\\d+\\s*[|/–—-]\\s*.*$")) ||
                trimmed.matches(Regex("^.*[|/–—-]\\s*\\d+$"))
    }

    private fun dehyphenateLines(lines: List<RawPdfLine>) {
        var i = 0
        while (i < lines.size - 1) {
            val cur = lines[i]
            val next = lines[i + 1]

            val match = Regex("([A-Za-zÄÖÜäöüß]+)\\s*[-‐‑‒–—\u00ad]\\s*$").find(cur.text)
            if (match != null) {
                val prefix = match.groupValues[1]
                val nextTrimmed = next.text.trimStart()

                val lowerContMatch = Regex("^([a-zäöüß]+)(.*)$").find(nextTrimmed)
                if (lowerContMatch != null) {
                    val cont = lowerContMatch.groupValues[1]
                    val remainder = lowerContMatch.groupValues[2].trim()
                    cur.text = cur.text.substring(0, match.range.first) + prefix + cont
                    next.text = remainder
                } else {
                    val upperContMatch = Regex("^([A-ZÄÖÜ][a-zäöüß]+)(.*)$").find(nextTrimmed)
                    if (upperContMatch != null) {
                        val cont = upperContMatch.groupValues[1]
                        val remainder = upperContMatch.groupValues[2].trim()
                        cur.text = cur.text.substring(0, match.range.first) + prefix + "-" + cont
                        next.text = remainder
                    }
                }
            }
            i++
        }
    }

    private fun buildHtmlContent(
        lines: List<RawPdfLine>,
        bodyFontSize: Float,
        bodyFontName: String,
        normalLineSpacing: Float
    ): String {
        val html = StringBuilder()
        val currentPara = StringBuilder()
        var inList = false
        var inToc = false

        fun flushPara() {
            if (currentPara.isNotBlank()) {
                val text = currentPara.toString().trim()
                if (text.isNotBlank()) {
                    html.append("<p>${escapeHtml(text)}</p>\n")
                }
                currentPara.clear()
            }
        }

        fun closeList() {
            if (inList) {
                html.append("</ul>\n")
                inList = false
            }
        }

        val majorKeywords = setOf(
            "executive summary", "abstract", "zusammenfassung",
            "content", "contents", "table of contents", "inhaltsverzeichnis",
            "references", "literaturverzeichnis", "bibliography",
            "acknowledgments", "acknowledgements", "danksagung",
            "appendix", "anhang", "conclusion", "fazit"
        )

        val nonBlankLines = lines.filter { it.text.isNotBlank() }

        for (idx in nonBlankLines.indices) {
            val line = nonBlankLines[idx]
            val text = line.text
            val lower = text.lowercase()

            // 1. Table of contents detection
            if (lower in setOf("content", "contents", "table of contents", "inhaltsverzeichnis")) {
                flushPara()
                closeList()
                inToc = true
                html.append("<h2>${escapeHtml(text)}</h2>\n")
                continue
            }

            if (inToc) {
                if (text.matches(Regex(".*\\s+\\d+$"))) {
                    html.append("<p class=\"toc-item\">${escapeHtml(text)}</p>\n")
                    continue
                } else if (line.fontSize >= bodyFontSize * 1.3f || line.page > 3) {
                    inToc = false
                }
            }

            // 2. Bullet list detection
            val bulletMatch = Regex("^[•\\u2022\\u25cf\\u25cb\\u25aa\\u25ab\\*]\\s*(.*)$").find(text)
                ?: Regex("^-\\s+([A-Za-zÄÖÜäöü].*)$").find(text)

            if (bulletMatch != null) {
                flushPara()
                if (!inList) {
                    html.append("<ul>\n")
                    inList = true
                }
                val itemText = bulletMatch.groupValues[1]
                html.append("<li>${escapeHtml(itemText)}</li>\n")
                continue
            } else {
                closeList()
            }

            // 3. Caption detection (Figure / Table)
            if (text.matches(Regex("^(?:Figure|Abbildung|Abb\\.|Table|Tabelle|Tab\\.)\\s+\\d+.*$", RegexOption.IGNORE_CASE))) {
                flushPara()
                html.append("<p class=\"caption\"><em>${escapeHtml(text)}</em></p>\n")
                continue
            }

            // 4. Heading classification
            val isNumberedSection = text.matches(Regex("^(\\d+(\\.\\d+)*\\.?\\s+[A-ZÄÖÜ].*)$"))
            val isChapterPrefix = text.matches(Regex("^(?:Kapitel|Chapter|Section|Teil|Part)\\s+\\d+.*$", RegexOption.IGNORE_CASE))
            val isMajorKeyword = lower in majorKeywords

            val isMajorHeading = (line.fontSize >= bodyFontSize * 1.32f && text.length in 3..90) ||
                    (isNumberedSection && text.length in 3..90 && !text.endsWith(",")) ||
                    (isChapterPrefix && text.length in 3..90) ||
                    (isMajorKeyword && text.length in 3..50)

            val isSubHeading = !isMajorHeading && (
                    (line.fontSize >= bodyFontSize * 1.14f && text.length in 3..75 && !text.endsWith(",") && !text.endsWith(".")) ||
                    (line.isBold && text.length in 3..60 && !text.endsWith(",") && !text.endsWith(".") && !text.endsWith(";")) ||
                    (line.fontName != bodyFontName && text.length in 3..60 && !text.endsWith(",") && !text.endsWith(".") && !text.endsWith(";") && !text.endsWith(":"))
            )

            if (isMajorHeading) {
                flushPara()
                html.append("<h2>${escapeHtml(text)}</h2>\n")
                continue
            } else if (isSubHeading) {
                flushPara()
                html.append("<h3>${escapeHtml(text)}</h3>\n")
                continue
            }

            // 5. Body Paragraph construction
            if (currentPara.isEmpty()) {
                currentPara.append(text)
            } else {
                val prevLine = nonBlankLines[idx - 1]
                val samePage = prevLine.page == line.page
                val verticalGap = line.y - prevLine.y

                val isVisualParaBreak = samePage && verticalGap > 1.65f * normalLineSpacing

                val prevEndsTerminal = prevLine.text.endsWith(".") || prevLine.text.endsWith("!") ||
                        prevLine.text.endsWith("?") || prevLine.text.endsWith(".\"") || prevLine.text.endsWith("!”")
                val nextStartsCapital = text.firstOrNull()?.isUpperCase() == true

                val isPageBoundaryParaBreak = !samePage && prevEndsTerminal && nextStartsCapital

                if (isVisualParaBreak || isPageBoundaryParaBreak) {
                    flushPara()
                    currentPara.append(text)
                } else {
                    currentPara.append(" ").append(text)
                }
            }
        }

        flushPara()
        closeList()
        return html.toString()
    }

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
