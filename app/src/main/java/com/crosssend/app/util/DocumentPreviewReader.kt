package com.crosssend.app.util

import android.content.Context
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.data.local.DocumentEntity
import com.crosssend.app.data.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile

object DocumentPreviewReader {

    suspend fun readPreviewText(document: DocumentEntity, maxChars: Int = 1200, context: Context? = null): String = withContext(Dispatchers.IO) {
        val ctx = context ?: try { CrossSendApp.instance } catch (_: Exception) { null }
        val file = File(document.localPath)
        if (!file.exists() || !file.canRead()) {
            return@withContext ctx?.getString(R.string.proc_preview_file_not_found)
                ?: "File not found on device (it may have been deleted or moved)."
        }

        try {
            when (document.type) {
                DocumentType.ARTICLE, DocumentType.BOOK -> {
                    if (file.name.endsWith(".epub", ignoreCase = true)) {
                        readEpubPreview(file, maxChars, ctx)
                    } else if (file.name.endsWith(".xtch", ignoreCase = true) || file.name.endsWith(".xtc", ignoreCase = true)) {
                        readXtchPreview(file, document, ctx)
                    } else if (file.name.endsWith(".pdf", ignoreCase = true)) {
                        readPdfPreview(file, maxChars, ctx)
                    } else if (file.name.endsWith(".acsm", ignoreCase = true)) {
                        readAcsmPreview(file, ctx)
                    } else {
                        readPlainTextPreview(file, maxChars, ctx)
                    }
                }
                DocumentType.TEXT -> readPlainTextPreview(file, maxChars, ctx)
                DocumentType.SLEEP -> ctx?.getString(R.string.proc_preview_sleepscreen)
                    ?: "Sleepscreen image for the e-reader (saved in /sleep/)."
            }
        } catch (e: Exception) {
            ctx?.getString(R.string.proc_preview_error, e.localizedMessage ?: "")
                ?: "Preview could not be loaded: ${e.localizedMessage}"
        }
    }

    private fun readEpubPreview(file: File, maxChars: Int, ctx: Context?): String {
        return ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .take(2000)
                .filter { entry ->
                    val name = entry.name.lowercase()
                    (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) &&
                            !name.contains("titlepage") && !name.contains("nav.") && !name.contains("toc.")
                }
                .sortedBy { it.name }
                .take(25)
                .toList()

            val sb = StringBuilder()
            for (entry in entries) {
                if (entry.size > 2L * 1024 * 1024) continue
                val rawHtml = zip.getInputStream(entry).use {
                    String(com.crosssend.app.converter.EInkImageOptimizer.readBoundedStream(it, 2L * 1024 * 1024), Charsets.UTF_8)
                }
                val doc = Jsoup.parse(rawHtml)
                doc.select("script, style, nav, header").remove()

                val paragraphs = doc.select("p, h1, h2, h3, blockquote, li")
                if (paragraphs.isNotEmpty()) {
                    for (p in paragraphs) {
                        val text = p.text().trim()
                        if (text.isNotBlank()) {
                            sb.append(text).append("\n\n")
                            if (sb.length >= maxChars) break
                        }
                    }
                } else {
                    val plain = doc.body().text().trim()
                    if (plain.isNotBlank()) {
                        sb.append(plain).append("\n\n")
                    }
                }
                if (sb.length >= maxChars) break
            }

            val result = sb.toString().trim()
            if (result.length > maxChars) {
                result.take(maxChars).substringBeforeLast(' ') + "…"
            } else if (result.isNotBlank()) {
                result
            } else {
                ctx?.getString(R.string.proc_preview_epub_no_text)
                    ?: "No text paragraphs found in this EPUB file."
            }
        }
    }

    private fun readPlainTextPreview(file: File, maxChars: Int, ctx: Context?): String {
        val readLimit = (maxChars * 4).coerceAtLeast(16 * 1024)
        val raw = file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(4096)
            val sb = StringBuilder()
            while (sb.length < readLimit) {
                val toRead = minOf(buf.size, readLimit - sb.length)
                val n = reader.read(buf, 0, toRead)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
        val text = raw.trim()
        return if (text.length > maxChars) {
            text.take(maxChars).substringBeforeLast(' ') + "…"
        } else {
            text.ifBlank {
                ctx?.getString(R.string.proc_preview_file_empty) ?: "The file is empty."
            }
        }
    }

    private fun readPdfPreview(file: File, maxChars: Int, ctx: Context?): String {
        return try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { pdfDoc ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(3, pdfDoc.numberOfPages)
                val text = stripper.getText(pdfDoc).trim()
                if (text.length > maxChars) {
                    text.take(maxChars).substringBeforeLast(' ') + "…"
                } else {
                    text.ifBlank {
                        ctx?.resources?.getQuantityString(R.plurals.proc_preview_pdf_pages, pdfDoc.numberOfPages, pdfDoc.numberOfPages)
                            ?: "PDF document (${pdfDoc.numberOfPages} pages)"
                    }
                }
            }
        } catch (e: Exception) {
            ctx?.getString(R.string.proc_preview_pdf_unavailable, e.message ?: "")
                ?: "PDF preview not available: ${e.message}"
        }
    }

    private fun readAcsmPreview(file: File, ctx: Context?): String {
        return try {
            if (file.length() > 2 * 1024 * 1024L) {
                return ctx?.getString(R.string.proc_preview_acsm_fallback) ?: "ACSM license file for e-book."
            }
            val content = file.inputStream().use {
                String(com.crosssend.app.converter.EInkImageOptimizer.readBoundedStream(it, 2L * 1024 * 1024), Charsets.UTF_8)
            }
            val titleRegex = "<(?:[a-zA-Z0-9]+:)?title[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?title>".toRegex(RegexOption.IGNORE_CASE)
            val creatorRegex = "<(?:[a-zA-Z0-9]+:)?creator[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?creator>".toRegex(RegexOption.IGNORE_CASE)
            val formatRegex = "<(?:[a-zA-Z0-9]+:)?format[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?format>".toRegex(RegexOption.IGNORE_CASE)

            val title = titleRegex.find(content)?.groupValues?.get(1)?.trim()
            val creator = creatorRegex.find(content)?.groupValues?.get(1)?.trim()
            val format = formatRegex.find(content)?.groupValues?.get(1)?.trim()

            buildString {
                append(ctx?.getString(R.string.proc_preview_acsm_header) ?: "📖 Adobe Content Server Message (ACSM)\n\n")
                if (!title.isNullOrBlank()) {
                    append(ctx?.getString(R.string.proc_preview_acsm_title, title) ?: "Title: $title\n")
                }
                if (!creator.isNullOrBlank()) {
                    append(ctx?.getString(R.string.proc_preview_acsm_author, creator) ?: "Author: $creator\n")
                }
                if (!format.isNullOrBlank()) {
                    append(ctx?.getString(R.string.proc_preview_acsm_format, format) ?: "Target format: $format\n")
                }
                append(ctx?.getString(R.string.proc_preview_acsm_footer) ?: "\nThis license file is provided in the root directory (/) for your e-reader.")
            }
        } catch (e: Exception) {
            ctx?.getString(R.string.proc_preview_acsm_fallback) ?: "ACSM license file for e-book."
        }
    }

    private fun readXtchPreview(file: File, document: DocumentEntity, ctx: Context?): String {
        val info = com.crosssend.app.converter.XtcBuilder.readXtchInfo(file)
        return buildString {
            if (info != null) {
                append(
                    ctx?.resources?.getQuantityString(
                        R.plurals.proc_preview_xtch_summary,
                        info.pageCount,
                        info.pageCount,
                        info.width,
                        info.height
                    ) ?: "Original Layout (XTCH) · ${info.pageCount} pages · ${info.width}×${info.height} px"
                )
            } else {
                append(ctx?.getString(R.string.proc_preview_xtch_fallback) ?: "Original layout document (XTCH)")
            }
            if (!document.excerpt.isNullOrBlank()) {
                append("\n\n").append(document.excerpt)
            }
        }
    }
}
