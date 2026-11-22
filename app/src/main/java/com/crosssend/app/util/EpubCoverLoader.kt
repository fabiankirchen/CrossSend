package com.crosssend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.crosssend.app.data.local.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object EpubCoverLoader {
    private const val MAX_COVER_CACHE_FILES = 150
    private const val MAX_COVER_CACHE_BYTES = 20L * 1024L * 1024L

    private val memoryCache = object : LruCache<String, Bitmap>(
        minOf((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt(), 24 * 1024).coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    private fun safeCoverFileName(docFileName: String): String {
        return File(docFileName).name.ifBlank { "cover" }
    }

    private fun evictMemoryCacheForFile(docFileName: String) {
        val suffix = "_${safeCoverFileName(docFileName)}"
        memoryCache.snapshot().keys
            .filter { it.endsWith(suffix) }
            .forEach { memoryCache.remove(it) }
    }

    private fun cleanupStaleDiskCacheFiles(cacheDir: File, documentId: Long, currentFileName: String) {
        val prefix = "cover_${documentId}_"
        cacheDir.listFiles { file ->
            val name = file.name
            name.startsWith(prefix) && name.endsWith(".jpg") && name != currentFileName
        }?.forEach { staleFile ->
            staleFile.delete()
        }
    }

    private fun pruneDiskCacheIfNeeded(cacheDir: File) {
        try {
            val files = cacheDir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
            var totalBytes = files.sumOf { it.length() }
            while (files.size > MAX_COVER_CACHE_FILES || totalBytes > MAX_COVER_CACHE_BYTES) {
                if (files.isEmpty()) break
                val oldest = files.removeAt(0)
                totalBytes -= oldest.length()
                oldest.delete()
            }
        } catch (_: Exception) {}
    }

    fun saveColorCoverForDocument(context: Context, docFileName: String, sourceColorFile: File?) {
        if (sourceColorFile == null || !sourceColorFile.exists()) return
        try {
            val safeName = safeCoverFileName(docFileName)
            val coversDir = File(context.filesDir, "library/covers").apply { mkdirs() }
            val destFile = File(coversDir, "$safeName.jpg")
            sourceColorFile.copyTo(destFile, overwrite = true)
            sourceColorFile.delete()
            evictMemoryCacheForFile(safeName)
        } catch (_: Exception) {}
    }

    fun deleteSidecarCoverForDocument(context: Context, docFileName: String) {
        try {
            val safeName = safeCoverFileName(docFileName)
            File(context.filesDir, "library/covers/$safeName.jpg").delete()
            evictMemoryCacheForFile(safeName)
        } catch (_: Exception) {}
    }

    /**
     * Preserves an existing EPUB document's high-quality editorial cover (either its existing
     * color sidecar or the embedded EPUB cover image) under [newDocFileName] before
     * mode conversion deletes the prior file.
     */
    fun preserveOrExtractCoverForNewFileName(
        context: Context,
        previousDoc: DocumentEntity?,
        newDocFileName: String
    ): Boolean {
        if (previousDoc == null) return false
        if (!previousDoc.fileName.endsWith(".epub", ignoreCase = true)) return false
        val safePrevName = safeCoverFileName(previousDoc.fileName)
        val safeNewName = safeCoverFileName(newDocFileName)
        val coversDir = File(context.filesDir, "library/covers").apply { mkdirs() }
        val destFile = File(coversDir, "$safeNewName.jpg")

        val existingSidecar = File(coversDir, "$safePrevName.jpg")
        if (existingSidecar.exists() && existingSidecar.canRead()) {
            return try {
                if (existingSidecar.canonicalPath != destFile.canonicalPath) {
                    existingSidecar.copyTo(destFile, overwrite = true)
                }
                evictMemoryCacheForFile(safeNewName)
                true
            } catch (_: Exception) {
                false
            }
        }

        val prevFile = File(previousDoc.localPath)
        if (prevFile.exists() && prevFile.canRead() && prevFile.name.endsWith(".epub", ignoreCase = true)) {
            val coverBytes = extractCoverImageBytesFromEpub(prevFile) ?: return false
            return try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, boundsOpts)
                if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return false
                var inSampleSize = 1
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                while (maxDim / (inSampleSize * 2) >= 600) {
                    inSampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, decodeOpts) ?: return false
                FileOutputStream(destFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                bmp.recycle()
                evictMemoryCacheForFile(safeNewName)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun extractCoverImageBytesFromEpub(epubFile: File): ByteArray? {
        return try {
            ZipFile(epubFile).use { zip ->
                val entries = zip.entries().asSequence().take(2000).toList()
                val coverEntry = entries.firstOrNull { entry ->
                    val name = entry.name.lowercase()
                    (name.contains("cover") || name.endsWith("cover.jpg") || name.endsWith("cover.png")) &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))
                } ?: entries.firstOrNull { entry ->
                    val name = entry.name.lowercase()
                    name.contains("images/") &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))
                } ?: return@use null

                if (coverEntry.size > 15L * 1024 * 1024) return@use null
                zip.getInputStream(coverEntry).use {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedStream(it, 15L * 1024 * 1024)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadCover(context: Context, document: DocumentEntity): Bitmap? = withContext(Dispatchers.IO) {
        val safeName = safeCoverFileName(document.fileName)
        val cacheKey = "${document.id}_$safeName"
        memoryCache.get(cacheKey)?.let { return@withContext it }

        // 1. First check if a full-color sidecar cover exists
        val sidecarCoverFile = File(context.filesDir, "library/covers/$safeName.jpg")
        if (sidecarCoverFile.exists() && sidecarCoverFile.canRead()) {
            try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(sidecarCoverFile.absolutePath, boundsOpts)
                val rawWidth = boundsOpts.outWidth
                val rawHeight = boundsOpts.outHeight
                if (rawWidth > 0 && rawHeight > 0) {
                    var inSampleSize = 1
                    val maxDim = maxOf(rawWidth, rawHeight)
                    while (maxDim / (inSampleSize * 2) >= 500) {
                        inSampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFile(sidecarCoverFile.absolutePath, decodeOpts)
                    if (bmp != null) {
                        memoryCache.put(cacheKey, bmp)
                        return@withContext bmp
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Otherwise fall back to extracting the cover from File(document.localPath) as before
        val path = document.localPath
        val epubFile = File(path)
        if (!epubFile.exists() || !epubFile.canRead()) return@withContext null

        // Disk cache check
        val diskCacheKey = "cover_${document.id}_${epubFile.lastModified()}"
        val cacheDir = File(context.cacheDir, "covers").apply { mkdirs() }
        val diskCacheFile = File(cacheDir, "$diskCacheKey.jpg")
        cleanupStaleDiskCacheFiles(cacheDir, document.id, diskCacheFile.name)

        if (diskCacheFile.exists()) {
            val cachedOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val cachedBmp = BitmapFactory.decodeFile(diskCacheFile.absolutePath, cachedOpts)
            if (cachedBmp != null) {
                memoryCache.put(cacheKey, cachedBmp)
                return@withContext cachedBmp
            }
        }

        // XTCH / XTC files use their downloaded lead-image sidecar cover (or the clean M3 card fallback if none)
        if (epubFile.name.endsWith(".xtch", ignoreCase = true) || epubFile.name.endsWith(".xtc", ignoreCase = true)) {
            return@withContext null
        }

        // Extract from EPUB zip
        val bytes = extractCoverImageBytesFromEpub(epubFile)
        if (bytes != null) {
            try {
                // Downsample to max ~600px width/height for fast card rendering and low RAM
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                var inSampleSize = 1
                val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                while (maxDim / (inSampleSize * 2) >= 400) {
                    inSampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565 // Memory-friendly
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                if (bitmap != null) {
                    try {
                        FileOutputStream(diskCacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        pruneDiskCacheIfNeeded(cacheDir)
                    } catch (_: Exception) {
                        // ignore disk cache write failure
                    }
                    memoryCache.put(cacheKey, bitmap)
                    return@withContext bitmap
                }
            } catch (_: Exception) {}
        }

        null
    }
}
