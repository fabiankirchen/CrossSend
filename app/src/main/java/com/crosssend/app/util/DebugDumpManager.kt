package com.crosssend.app.util

import android.content.Context
import android.util.Log
import com.crosssend.app.converter.ExtractedArticle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugDumpManager {
    private const val TAG = "CrossSendDebug"
    private const val PREFS_NAME = "crosssend_prefs"
    private const val PREF_DEBUG_ENABLED = "debug_dump_enabled"

    fun getDumpsRootDir(context: Context): File {
        return File(context.filesDir, "debug_dumps").apply { mkdirs() }
    }

    fun getDebugDir(context: Context): File = getDumpsRootDir(context)

    fun isEnabled(context: Context): Boolean = isDebugDumpEnabled(context)

    fun isDebugDumpEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_DEBUG_ENABLED, false) // Opt-in only
    }

    fun setDebugDumpEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DEBUG_ENABLED, enabled)
            .apply()
    }

    fun clearAllDumps(context: Context): Int {
        try {
            context.getExternalFilesDir(null)?.let {
                val legacy = File(it, "debug_dumps")
                if (legacy.exists()) legacy.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear legacy debug dumps", e)
        }
        val dir = getDumpsRootDir(context)
        val files = dir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.deleteRecursively()) count++
        }
        return count
    }

    fun getDumpCount(context: Context): Int {
        val dir = getDumpsRootDir(context)
        return dir.listFiles()?.filter { it.isDirectory }?.size ?: 0
    }

    fun dumpItem(
        context: Context,
        sourceType: String, // "WEB_URL", "HTML_SHARE", "TEXT_SHARE", "RSS_FEED", "FILE"
        sourceUrl: String?,
        inputSubject: String?,
        rawInput: String,
        article: ExtractedArticle?,
        epubFile: File?
    ) {
        if (!isDebugDumpEnabled(context)) return

        try {
            // Delete legacy external debug dumps if present
            try {
                context.getExternalFilesDir(null)?.let { externalBase ->
                    val legacyExternalDir = File(externalBase, "debug_dumps")
                    if (legacyExternalDir.exists()) {
                        legacyExternalDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean legacy external debug dumps", e)
            }

            // Cleanup dumps older than 7 days
            try {
                val cutoff = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
                val rootDir = getDumpsRootDir(context)
                rootDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.lastModified() < cutoff) {
                        file.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup old debug dumps", e)
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rawTitle = article?.title ?: inputSubject ?: "debug_item"
            val safeTitle = rawTitle.replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(40)
            val dumpDir = File(getDumpsRootDir(context), "${timestamp}_${safeTitle}").apply { mkdirs() }

            // 1. Raw Input
            val isHtml = rawInput.trim().startsWith("<") || sourceType.contains("HTML") || sourceType.contains("WEB") || sourceType.contains("RSS")
            val inputFileName = if (isHtml) "input_raw.html" else "input_raw.txt"
            File(dumpDir, inputFileName).writeText(rawInput)

            // 2. Metadata
            val metaBuilder = StringBuilder()
            metaBuilder.appendLine("=== CROSSSEND DEBUG DUMP ===")
            metaBuilder.appendLine("Timestamp: $timestamp")
            metaBuilder.appendLine("Source Type: $sourceType")
            metaBuilder.appendLine("Source URL: ${sourceUrl ?: "n/a"}")
            metaBuilder.appendLine("Input Subject/Title: ${inputSubject ?: "n/a"}")
            metaBuilder.appendLine("")
            metaBuilder.appendLine("--- Extracted Article Info ---")
            metaBuilder.appendLine("Extracted Title: ${article?.title ?: "n/a"}")
            metaBuilder.appendLine("Extracted Subtitle: ${article?.subtitle ?: "n/a"}")
            metaBuilder.appendLine("Extracted Author: ${article?.author ?: "n/a"}")
            metaBuilder.appendLine("Site Name: ${article?.siteName ?: "n/a"}")
            metaBuilder.appendLine("Published Date: ${article?.publishedDate ?: "n/a"}")
            metaBuilder.appendLine("Word Count: ${article?.wordCount ?: 0}")
            metaBuilder.appendLine("Reading Time: ${article?.readingTimeMinutes ?: 0} min")
            metaBuilder.appendLine("Lead Image URL: ${article?.leadImageUrl ?: "n/a"}")
            metaBuilder.appendLine("Inline Images Count: ${article?.imageUrls?.size ?: 0}")
            metaBuilder.appendLine("")
            metaBuilder.appendLine("Output EPUB: ${epubFile?.absolutePath ?: "n/a"}")

            File(dumpDir, "metadata.txt").writeText(metaBuilder.toString())

            // 3. Extracted Clean HTML
            if (article != null && article.contentHtml.isNotBlank()) {
                File(dumpDir, "extracted_clean.html").writeText(article.contentHtml)
            }

            // 4. Output EPUB
            if (epubFile != null && epubFile.exists()) {
                val destEpub = File(dumpDir, "output.epub")
                epubFile.copyTo(destEpub, overwrite = true)
            }

            Log.i(TAG, "✓ DEBUG DUMP RECORDED: ${dumpDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record debug dump", e)
        }
    }
}
