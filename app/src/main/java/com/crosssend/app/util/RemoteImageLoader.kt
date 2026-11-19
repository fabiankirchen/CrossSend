package com.crosssend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.crosssend.app.converter.EInkImageOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object RemoteImageLoader {
    private const val TAG = "RemoteImageLoader"
    private const val MAX_DISK_CACHE_FILES = 200
    private const val MAX_DISK_CACHE_BYTES = 30L * 1024L * 1024L
    private const val PRUNE_INTERVAL_MS = 60_000L
    private const val HEX_CHARS = "0123456789abcdef"

    private val memoryCache by lazy {
        val maxKb = minOf((Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt(), 16 * 1024)
            .coerceAtLeast(4 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
    }
    private val pruneLock = Any()
    @Volatile
    private var lastPruneTimeMs: Long = 0L

    internal fun cacheFileNameForUrl(imageUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(imageUrl.toByteArray(Charsets.UTF_8))
        val hex = buildString(32) {
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                append(HEX_CHARS[b ushr 4])
                append(HEX_CHARS[b and 0x0F])
            }
        }
        return "thumb_$hex.jpg"
    }

    internal fun pruneDiskCacheIfNeeded(
        cacheDir: File,
        nowMs: Long = System.currentTimeMillis(),
        force: Boolean = false
    ) {
        if (!force && nowMs - lastPruneTimeMs < PRUNE_INTERVAL_MS) return
        synchronized(pruneLock) {
            if (!force && nowMs - lastPruneTimeMs < PRUNE_INTERVAL_MS) return
            lastPruneTimeMs = nowMs
            val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
            var fileCount = files.size
            var totalBytes = files.sumOf { it.length() }
            if (fileCount <= MAX_DISK_CACHE_FILES && totalBytes <= MAX_DISK_CACHE_BYTES) {
                return
            }
            for (file in files.sortedBy { it.lastModified() }) {
                if (fileCount <= MAX_DISK_CACHE_FILES && totalBytes <= MAX_DISK_CACHE_BYTES) {
                    break
                }
                val fileLength = file.length()
                if (file.delete()) {
                    fileCount--
                    totalBytes = (totalBytes - fileLength).coerceAtLeast(0L)
                }
            }
        }
    }

    suspend fun loadThumbnail(context: Context, imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        if (imageUrl.isBlank()) return@withContext null

        // 1. SSRF check
        if (!EInkImageOptimizer.isSafeExternalImageUrl(imageUrl)) {
            Log.w(TAG, "Blocked unsafe remote image URL: $imageUrl")
            return@withContext null
        }

        // 2. Memory cache check
        memoryCache.get(imageUrl)?.let { return@withContext it }

        // 3. Disk cache check
        val cacheDir = File(context.cacheDir, "rss_thumbs").apply { mkdirs() }
        val diskFile = File(cacheDir, cacheFileNameForUrl(imageUrl))

        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(diskFile.absolutePath, boundsOpts)
                val rawW = boundsOpts.outWidth
                val rawH = boundsOpts.outHeight
                if (rawW > 0 && rawH > 0) {
                    var inSampleSize = 1
                    val maxDim = maxOf(rawW, rawH)
                    while (maxDim / (inSampleSize * 2) >= 450) {
                        inSampleSize *= 2
                    }
                    val decodeOpts = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val cachedBmp = BitmapFactory.decodeFile(diskFile.absolutePath, decodeOpts)
                    if (cachedBmp != null) {
                        diskFile.setLastModified(System.currentTimeMillis())
                        memoryCache.put(imageUrl, cachedBmp)
                        return@withContext cachedBmp
                    }
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "OOM decoding cached thumbnail $imageUrl")
                memoryCache.evictAll()
                return@withContext null
            } catch (e: Exception) {
                Log.w(TAG, "Error decoding cached thumbnail $imageUrl", e)
            }
        }

        // 4. Download from network
        try {
            val client = EInkImageOptimizer.getSafeInternetClient(connectTimeoutSeconds = 8, readTimeoutSeconds = 10)
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; CrossSend)")
                .build()

            val imageBytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val responseBody = response.body ?: return@withContext null
                try {
                    EInkImageOptimizer.readBoundedBytes(responseBody, 15L * 1024 * 1024)
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail exceeds limit or failed reading: $imageUrl - ${e.message}")
                    return@withContext null
                }
            }

            if (imageBytes.isEmpty()) return@withContext null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOpts)
            val rawW = boundsOpts.outWidth
            val rawH = boundsOpts.outHeight
            if (rawW <= 0 || rawH <= 0) return@withContext null

            var inSampleSize = 1
            val maxDim = maxOf(rawW, rawH)
            while (maxDim / (inSampleSize * 2) >= 450) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
                ?: return@withContext null

            // Save to disk cache and prune old thumbnails
            try {
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                pruneDiskCacheIfNeeded(cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save thumbnail to disk cache", e)
            }

            // Save to memory cache
            memoryCache.put(imageUrl, bitmap)
            bitmap
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM decoding thumbnail from network $imageUrl")
            memoryCache.evictAll()
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to load thumbnail $imageUrl: ${e.message}")
            null
        }
    }
}
