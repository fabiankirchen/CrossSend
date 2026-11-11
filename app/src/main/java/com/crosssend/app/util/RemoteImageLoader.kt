package com.crosssend.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.crosssend.app.CrossSendApp
import com.crosssend.app.converter.EInkImageOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object RemoteImageLoader {
    private const val TAG = "RemoteImageLoader"
    private val memoryCache = LruCache<String, Bitmap>(60)

    private val fallbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(EInkImageOptimizer.ssrfSafeDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(EInkImageOptimizer.httpsOnlyInterceptor)
            .addNetworkInterceptor(EInkImageOptimizer.httpsOnlyInterceptor)
            .addInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
            .addNetworkInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
            .build()
    }

    private fun getHttpClient(): OkHttpClient {
        return try {
            CrossSendApp.instance.wifiManager.getInternetOkHttpClientBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                .addNetworkInterceptor(EInkImageOptimizer.ssrfSafeInterceptor)
                .build()
        } catch (_: Exception) {
            fallbackClient
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
        val diskFile = File(cacheDir, "thumb_${imageUrl.hashCode()}.jpg")

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
            val client = getHttpClient()
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; CrossSend)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext null
            }

            val responseBody = response.body ?: return@withContext null
            val imageBytes = try {
                responseBody.use { body ->
                    EInkImageOptimizer.readBoundedBytes(body, 15L * 1024 * 1024)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail exceeds limit or failed reading: $imageUrl - ${e.message}")
                return@withContext null
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

            // Save to disk cache
            try {
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
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
