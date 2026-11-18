package com.crosssend.app.converter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class OptimizedImage(
    val originalUrl: String,
    val localFile: File,
    val relativePath: String, // e.g. "images/img_1.jpg"
    val width: Int,
    val height: Int,
    val mediaType: String = "image/jpeg",
    val isCover: Boolean = false,
    val colorCoverFile: File? = null
)

class EInkImageOptimizer(
    private val cacheDir: File,
    private val context: Context? = null
) {
    constructor(context: Context) : this(context.cacheDir, context)

    private val tag = "EInkImageOptimizer"

    companion object {
        fun isSafeInetAddress(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress || addr.isMulticastAddress) {
                return false
            }
            val bytes = addr.address
            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10 (CGNAT), 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16
                if (b0 == 0 || b0 == 10 || b0 == 127 ||
                    (b0 == 100 && b1 in 64..127) ||
                    (b0 == 169 && b1 == 254) ||
                    (b0 == 172 && b1 in 16..31) ||
                    (b0 == 192 && b1 == 168)) {
                    return false
                }
            } else if (bytes.size == 16) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // Unique Local IPv6 (fc00::/7) or Link-Local (fe80::/10)
                if ((b0 and 0xFE) == 0xFC || (b0 == 0xFE && (b1 and 0xC0) == 0x80)) {
                    return false
                }
                // IPv4-mapped IPv6 ::ffff:x.x.x.x
                val isV4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                    bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
                if (isV4Mapped) {
                    val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    if (!isSafeInetAddress(v4)) return false
                }
            }
            return true
        }

        fun wrapWithSafeDns(baseDns: Dns = Dns.SYSTEM): Dns {
            return object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val addresses = baseDns.lookup(hostname)
                    val safe = addresses.filter { isSafeInetAddress(it) }
                    if (safe.isEmpty() || safe.size != addresses.size) {
                        throw UnknownHostException("Blocked SSRF resolution to internal/private IP for host: $hostname")
                    }
                    return safe
                }
            }
        }

        val ssrfSafeDns: Dns = wrapWithSafeDns(Dns.SYSTEM)

        fun readBoundedBytes(body: ResponseBody, maxBytes: Long = 15L * 1024 * 1024): ByteArray {
            val contentLength = body.contentLength()
            if (contentLength > maxBytes) {
                throw java.io.IOException("Response content-length ($contentLength) exceeds limit ($maxBytes bytes)")
            }
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) {
                throw java.io.IOException("Response stream exceeds limit ($maxBytes bytes)")
            }
            return source.buffer.readByteArray()
        }

        fun readBoundedString(body: ResponseBody, maxBytes: Long = 10L * 1024 * 1024): String {
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return String(readBoundedBytes(body, maxBytes), charset)
        }

        fun readBoundedStream(input: java.io.InputStream, maxBytes: Long = 15L * 1024 * 1024): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw java.io.IOException("Stream exceeds maximum allowed size ($maxBytes bytes)")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }

        fun createSleepScreenBmp(bitmap: Bitmap): ByteArray {
            return bitmapTo1BitBmp(bitmap)
        }

        enum class CrossPointHardwareProfile(
            val id: String,
            val displayName: String,
            val screenWidth: Int,
            val screenHeight: Int,
            val diagonalInches: Double
        ) {
            X4("X4", "Xteink X4 / X4 Pro / X4 Classic", 480, 800, 4.3),
            PICCO("PICCO", "Onyx Boox Picco (Pico)", 480, 800, 3.97),
            X3("X3", "Xteink X3", 528, 792, 3.7),
            EEGOREAD_A4("EEGOREAD_A4", "EEGOREAD A4 / A4 Lite", 552, 768, 3.98),
            RETERMINAL("RETERMINAL", "Seeed reTerminal Sticky / Metalio E-Ink 4", 480, 800, 3.97),
            M5PAPER("M5PAPER", "M5Stack M5Paper / M5PaperS3", 540, 960, 4.7),
            MURPHY_M4("MURPHY_M4", "Murphy M4", 480, 800, 4.0);

            companion object {
                fun fromModelString(model: String?): CrossPointHardwareProfile {
                    if (model.isNullOrBlank()) return X4
                    val upper = model.uppercase()
                    return when {
                        upper.contains("X3") -> X3
                        upper.contains("A4") || upper.contains("EEGOREAD") || upper.contains("EEGO") -> EEGOREAD_A4
                        upper.contains("M5") || upper.contains("PAPER") -> M5PAPER
                        upper.contains("PICCO") || upper.contains("PICO") || upper.contains("BOOX") -> PICCO
                        upper.contains("MURPHY") || upper.contains("M4") -> MURPHY_M4
                        upper.contains("RETERMINAL") || upper.contains("METALIO") || upper.contains("STICKY") -> RETERMINAL
                        else -> X4
                    }
                }
            }
        }

        fun saveDetectedModel(context: Context, model: String) {
            context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("device_model", model)
                .apply()
        }

        fun getTargetScreenDimensions(context: Context? = null): Pair<Int, Int> {
            val ctx = context ?: try { com.crosssend.app.CrossSendApp.instance } catch (_: Exception) { null }
            val prefs = ctx?.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            val customW = prefs?.getInt("screen_width", 0)?.takeIf { it > 0 }
            val customH = prefs?.getInt("screen_height", 0)?.takeIf { it > 0 }
            if (customW != null && customH != null) {
                return customW to customH
            }

            val model = prefs?.getString("device_model", "X4") ?: "X4"
            val upper = model.uppercase()
            return when {
                upper.contains("X3") -> 528 to 792
                upper.contains("A4") || upper.contains("EEGOREAD") || upper.contains("EEGO") -> 552 to 768
                upper.contains("M5") || upper.contains("PAPER") -> 540 to 960
                else -> 480 to 800 // X4, PICCO, PICO, BOOX, MURPHY, RETERMINAL, METALIO, default
            }
        }

        fun applyAtkinsonDithering(source: Bitmap): Bitmap {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val lum = FloatArray(width * height)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            val outPixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = y * width + x
                    val oldVal = lum[idx].coerceIn(0f, 255f)
                    val newVal = if (oldVal > 127.5f) 255f else 0f
                    outPixels[idx] = if (newVal > 127.5f) -0x1 else -0x1000000

                    val error = (oldVal - newVal) / 8.0f

                    if (x + 1 < width) lum[y * width + (x + 1)] += error
                    if (x + 2 < width) lum[y * width + (x + 2)] += error
                    if (y + 1 < height) {
                        if (x - 1 >= 0) lum[(y + 1) * width + (x - 1)] += error
                        lum[(y + 1) * width + x] += error
                        if (x + 1 < width) lum[(y + 1) * width + (x + 1)] += error
                    }
                    if (y + 2 < height) {
                        lum[(y + 2) * width + x] += error
                    }
                }
            }

            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            result.setPixels(outPixels, 0, width, 0, 0, width, height)
            return result
        }

        fun bitmapTo1BitBmp(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val rowStride = ((width + 31) / 32) * 4
            val imageSize = rowStride * height
            val fileSize = 62 + imageSize

            val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

            // BITMAPFILEHEADER (14 bytes)
            buffer.put('B'.code.toByte())
            buffer.put('M'.code.toByte())
            buffer.putInt(fileSize)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putInt(62) // offset to pixel data (14 + 40 + 8)

            // BITMAPINFOHEADER (40 bytes)
            buffer.putInt(40)
            buffer.putInt(width)
            buffer.putInt(height) // positive = bottom-up
            buffer.putShort(1) // biPlanes
            buffer.putShort(1) // biBitCount = 1 (monochrome)
            buffer.putInt(0) // biCompression = BI_RGB
            buffer.putInt(imageSize)
            buffer.putInt(2835) // biXPelsPerMeter (~72 DPI)
            buffer.putInt(2835) // biYPelsPerMeter (~72 DPI)
            buffer.putInt(2) // biClrUsed = 2
            buffer.putInt(2) // biClrImportant = 2

            // Color Table (8 bytes: Index 0 = Black, Index 1 = White)
            buffer.put(0x00.toByte()) // Blue
            buffer.put(0x00.toByte()) // Green
            buffer.put(0x00.toByte()) // Red
            buffer.put(0x00.toByte()) // Reserved

            buffer.put(0xFF.toByte()) // Blue
            buffer.put(0xFF.toByte()) // Green
            buffer.put(0xFF.toByte()) // Red
            buffer.put(0x00.toByte()) // Reserved

            val pixels = IntArray(width)

            // Bottom-up: y from height - 1 down to 0
            for (y in height - 1 downTo 0) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                val rowBytes = ByteArray(rowStride)
                for (x in 0 until width) {
                    val color = pixels[x]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    val isWhite = (r * 299 + g * 587 + b * 114) / 1000 > 127
                    if (isWhite) {
                        val byteIndex = x / 8
                        val bitIndex = 7 - (x and 7)
                        rowBytes[byteIndex] = (rowBytes[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                    }
                }
                buffer.put(rowBytes)
            }

            return buffer.array()
        }

        fun bitmapTo24BitBmp(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val rowPadding = (4 - (width * 3) % 4) % 4
            val rowStride = width * 3 + rowPadding
            val imageSize = rowStride * height
            val fileSize = 54 + imageSize

            val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)

            // BITMAPFILEHEADER (14 bytes)
            buffer.put('B'.code.toByte())
            buffer.put('M'.code.toByte())
            buffer.putInt(fileSize)
            buffer.putShort(0)
            buffer.putShort(0)
            buffer.putInt(54)

            // BITMAPINFOHEADER (40 bytes)
            buffer.putInt(40)
            buffer.putInt(width)
            buffer.putInt(height)
            buffer.putShort(1)
            buffer.putShort(24)
            buffer.putInt(0)
            buffer.putInt(imageSize)
            buffer.putInt(2835)
            buffer.putInt(2835)
            buffer.putInt(0)
            buffer.putInt(0)

            val pixels = IntArray(width)
            val paddingBytes = ByteArray(rowPadding)

            for (y in height - 1 downTo 0) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val color = pixels[x]
                    val r = (color shr 16 and 0xFF).toByte()
                    val g = (color shr 8 and 0xFF).toByte()
                    val b = (color and 0xFF).toByte()
                    buffer.put(b)
                    buffer.put(g)
                    buffer.put(r)
                }
                if (rowPadding > 0) {
                    buffer.put(paddingBytes)
                }
            }

            return buffer.array()
        }

        fun isSafeExternalImageUrl(url: String): Boolean {
            return try {
                val uri = java.net.URI(url)
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme != "http" && scheme != "https") return false
                val rawHost = uri.host?.lowercase()?.trim() ?: return false
                if (rawHost.isBlank()) return false
                val host = rawHost.removeSurrounding("[", "]")
                if (host.isBlank() || host == "localhost") return false
                if (host.all { it.isDigit() || it == '.' } && !host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
                if (host.startsWith("0x", ignoreCase = true) || host.split('.').any { it.length > 1 && it.startsWith("0") && it.all { c -> c.isDigit() } }) return false
                if (host == "local" || host.endsWith(".local")) return false
                if (host == "internal" || host.endsWith(".internal")) return false
                if (host == "lan" || host.endsWith(".lan")) return false
                if (host == "0.0.0.0" || host == "::" || host == "::1" || host == "0:0:0:0:0:0:0:1") return false
                if (host.startsWith("fe80:")) return false
                if (host.startsWith("fc") || host.startsWith("fd")) return false
                if (host.startsWith("::ffff:127.") || host.startsWith("::ffff:10.") ||
                    host.startsWith("::ffff:192.168.") || host.startsWith("::ffff:169.254.")) return false
                if (host.startsWith("::ffff:172.")) {
                    val secondOctet = host.substringAfter("::ffff:172.").substringBefore('.').toIntOrNull()
                    if (secondOctet != null && secondOctet in 16..31) return false
                }
                if (host.startsWith("127.") || host.startsWith("10.") ||
                    host.startsWith("192.168.") || host.startsWith("169.254.")) return false
                if (host.startsWith("172.")) {
                    val secondOctet = host.substringAfter("172.").substringBefore('.').toIntOrNull()
                    if (secondOctet != null && secondOctet in 16..31) return false
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun isSafeExternalUrl(url: String): Boolean = isSafeExternalImageUrl(url)

        val httpsOnlyInterceptor = okhttp3.Interceptor { chain ->
            val req = chain.request()
            if (!req.isHttps) {
                throw java.io.IOException("Cleartext HTTP is blocked for external internet requests: ${req.url}")
            }
            val response = chain.proceed(req)
            if (!response.request.isHttps) {
                response.close()
                throw java.io.IOException("Blocked cleartext HTTP redirect target: ${response.request.url}")
            }
            if (response.isRedirect) {
                val location = response.header("Location")
                if (location != null) {
                    val resolved = response.request.url.resolve(location)
                    if (resolved == null || !resolved.isHttps) {
                        response.close()
                        throw java.io.IOException("Blocked cleartext HTTP redirect Location: $location")
                    }
                }
            }
            response
        }

        val ssrfSafeInterceptor = okhttp3.Interceptor { chain ->
            val reqUrl = chain.request().url.toString()
            if (!isSafeExternalImageUrl(reqUrl)) {
                throw java.io.IOException("Blocked unsafe external URL: $reqUrl")
            }
            val response = chain.proceed(chain.request())
            val finalUrl = response.request.url.toString()
            if (!isSafeExternalImageUrl(finalUrl)) {
                response.close()
                throw java.io.IOException("Blocked unsafe redirect target URL: $finalUrl")
            }
            if (response.isRedirect) {
                val location = response.header("Location")
                if (location != null) {
                    val resolved = response.request.url.resolve(location)?.toString()
                    if (resolved == null || !isSafeExternalImageUrl(resolved)) {
                        response.close()
                        throw java.io.IOException("Blocked unsafe redirect Location: $location")
                    }
                }
            }
            response
        }
    }

    fun isSafeExternalImageUrl(url: String): Boolean = Companion.isSafeExternalImageUrl(url)
    fun isSafeExternalUrl(url: String): Boolean = Companion.isSafeExternalImageUrl(url)

    private val client: OkHttpClient
        get() = try {
            com.crosssend.app.CrossSendApp.instance.wifiManager.getInternetOkHttpClientBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(ssrfSafeInterceptor)
                .addNetworkInterceptor(ssrfSafeInterceptor)
                .build()
        } catch (_: Exception) {
            OkHttpClient.Builder()
                .dns(ssrfSafeDns)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(httpsOnlyInterceptor)
                .addNetworkInterceptor(httpsOnlyInterceptor)
                .addInterceptor(ssrfSafeInterceptor)
                .addNetworkInterceptor(ssrfSafeInterceptor)
                .build()
        }

    // 380px fits comfortably on 480x800 screens, keeping decode times under 80ms on ESP32
    private val maxInlineDimension = 380
    private val maxCoverDimension = 300

    /**
     * Downloads an image, scales it down, converts to 8-bit grayscale, compresses as fast-decoding JPEG
     * and records exact width/height so ESP32 reader doesn't need to decode image during pagination!
     */
    suspend fun optimizeImage(
        imageUrl: String,
        index: Int,
        isCover: Boolean = false
    ): OptimizedImage? = withContext(Dispatchers.IO) {
        if (!isSafeExternalImageUrl(imageUrl)) {
            Log.w(tag, "Blocked unsafe image URL: $imageUrl")
            return@withContext null
        }
        try {
            val request = Request.Builder().url(imageUrl).build()
            val imageBytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "Failed to download image: $imageUrl, HTTP ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body ?: return@withContext null
                try {
                    readBoundedBytes(responseBody, 15L * 1024 * 1024)
                } catch (e: Exception) {
                    Log.w(tag, "Image exceeds byte limit ($imageUrl): ${e.message}")
                    return@withContext null
                }
            }

            // 1. Decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return@withContext null

            val maxDim = if (isCover) maxCoverDimension else maxInlineDimension

            // 2. Downsample
            var sampleSize = 1
            while (rawWidth / sampleSize > maxDim * 2 || rawHeight / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return@withContext null

            // 3. Scale precisely
            val scale = minOf(
                maxDim.toFloat() / originalBitmap.width,
                maxDim.toFloat() / originalBitmap.height,
                1.0f
            )
            val targetWidth = (originalBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (originalBitmap.height * scale).roundToInt().coerceAtLeast(1)

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
            } else {
                originalBitmap
            }

            // 4. Convert to Grayscale with E-Ink Contrast-Boost (Deeper blacks, crisp whites)
            val grayscaleBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(grayscaleBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            
            // Saturation 0 + Contrast Boost
            val satMatrix = ColorMatrix().apply { setSaturation(0f) }
            val contrast = 1.22f
            val brightness = -10f
            val contrastMatrix = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            satMatrix.postConcat(contrastMatrix)
            paint.colorFilter = ColorMatrixColorFilter(satMatrix)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

            // 5. Save to disk (Quality 60% is indistinguishable on 4-bit e-ink but decodes 3x faster)
            val imagesDir = File(cacheDir, "epub_images").apply { mkdirs() }
            val uniqueId = java.util.UUID.randomUUID().toString().take(8)
            val diskFileName = if (isCover) "cover_${uniqueId}.jpg" else "img_${uniqueId}_${index}.jpg"
            val epubRelativeName = if (isCover) "cover.jpg" else "img_${index}.jpg"
            val targetFile = File(imagesDir, diskFileName)

            FileOutputStream(targetFile).use { out ->
                grayscaleBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
            }

            var colorCoverFile: File? = null
            if (isCover) {
                val colorFile = File(imagesDir, "color_cover_${uniqueId}.jpg")
                FileOutputStream(colorFile).use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                colorCoverFile = colorFile
            }

            if (scaledBitmap != originalBitmap) originalBitmap.recycle()
            scaledBitmap.recycle()
            grayscaleBitmap.recycle()

            OptimizedImage(
                originalUrl = imageUrl,
                localFile = targetFile,
                relativePath = "images/$epubRelativeName",
                width = targetWidth,
                height = targetHeight,
                isCover = isCover,
                colorCoverFile = colorCoverFile
            )

        } catch (e: Exception) {
            Log.e(tag, "Error optimizing image $imageUrl", e)
            null
        }
    }

    /**
     * Safely decodes a downsampled bitmap from an image URI to prevent OutOfMemoryError.
     */
    fun decodeSampledBitmapFromUri(
        context: android.content.Context,
        uri: android.net.Uri,
        reqWidth: Int = 960,
        reqHeight: Int = 1600
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to decode bitmap from URI", e)
            null
        }
    }

    /**
     * Crops and scales any bitmap to target resolution (X4: 480x800, X3: 528x792) and applies
     * grayscale conversion with custom contrast and brightness adjustments.
     * panX: 0.0 (left) to 1.0 (right), default 0.5 (center)
     * panY: 0.0 (top) to 1.0 (bottom), default 0.5 (center)
     * zoom: 1.0 (fit) to 3.0 (zoomed in)
     */
    fun processSleepScreenBitmap(
        source: Bitmap,
        contrast: Float = 1.3f,
        brightness: Float = -8f,
        panX: Float = 0.5f,
        panY: Float = 0.5f,
        zoom: Float = 1.0f,
        targetWidth: Int = getTargetScreenDimensions(context).first,
        targetHeight: Int = getTargetScreenDimensions(context).second
    ): Bitmap {
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val srcRatio = source.width.toFloat() / source.height.toFloat()

        val clampedZoom = zoom.coerceIn(1.0f, 3.0f)
        val clampedPanX = panX.coerceIn(0.0f, 1.0f)
        val clampedPanY = panY.coerceIn(0.0f, 1.0f)

        val baseCropWidth: Float
        val baseCropHeight: Float

        if (srcRatio > targetRatio) {
            baseCropHeight = source.height.toFloat()
            baseCropWidth = baseCropHeight * targetRatio
        } else {
            baseCropWidth = source.width.toFloat()
            baseCropHeight = baseCropWidth / targetRatio
        }

        // Apply zoom: higher zoom means smaller crop window in the source bitmap
        val cropWidth = (baseCropWidth / clampedZoom).roundToInt().coerceIn(10, source.width)
        val cropHeight = (baseCropHeight / clampedZoom).roundToInt().coerceIn(10, source.height)

        val maxOffsetX = (source.width - cropWidth).coerceAtLeast(0)
        val maxOffsetY = (source.height - cropHeight).coerceAtLeast(0)

        val cropX = (maxOffsetX * clampedPanX).roundToInt().coerceIn(0, maxOffsetX)
        val cropY = (maxOffsetY * clampedPanY).roundToInt().coerceIn(0, maxOffsetY)

        val cropped = Bitmap.createBitmap(source, cropX, cropY, cropWidth, cropHeight)
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (cropped != source && cropped != scaled) {
            cropped.recycle()
        }

        val resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val satMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        satMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(satMatrix)

        canvas.drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle()

        val ditheredBitmap = applyAtkinsonDithering(resultBitmap)
        resultBitmap.recycle()
        return ditheredBitmap
    }

    /**
     * Converts raw image bytes into a 1-bit monochrome BMP with Atkinson dithering
     * at target hardware resolution (480x800 for X4, 528x792 for X3).
     */
    fun optimizeSleepImage(
        imageBytes: ByteArray,
        contrast: Float = 1.3f,
        brightness: Float = -8f,
        panX: Float = 0.5f,
        panY: Float = 0.5f,
        zoom: Float = 1.0f
    ): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            val (w, h) = getTargetScreenDimensions(context)

            var sampleSize = 1
            while (rawWidth / (sampleSize * 2) >= w && rawHeight / (sampleSize * 2) >= h) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return null

            val processedBitmap = processSleepScreenBitmap(
                source = originalBitmap,
                contrast = contrast,
                brightness = brightness,
                panX = panX,
                panY = panY,
                zoom = zoom,
                targetWidth = w,
                targetHeight = h
            )

            if (originalBitmap != processedBitmap) {
                originalBitmap.recycle()
            }

            val bmpBytes = bitmapTo1BitBmp(processedBitmap)
            processedBitmap.recycle()
            bmpBytes
        } catch (e: Exception) {
            Log.e(tag, "Failed to optimize sleep image", e)
            null
        }
    }

    /**
     * Converts a bitmap to a 1-bit monochrome BMP for sleepscreens.
     */
    fun createSleepScreenBmp(bitmap: Bitmap): ByteArray {
        return bitmapTo1BitBmp(bitmap)
    }

    /**
     * Saves a sleepscreen bitmap as a fast-loading JPEG to the specified file.
     */
    fun saveSleepScreenToFile(bitmap: Bitmap, outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to save sleepscreen bitmap", e)
            false
        }
    }

    /**
     * Scales any bitmap (e.g. PDF first page cover) to fit E-Ink display (max 480x800),
     * converts to 8-bit grayscale with contrast boost, and saves as an optimized JPEG.
     */
    fun optimizeBitmap(
        source: Bitmap,
        isCover: Boolean = true,
        fileName: String = if (isCover) "cover.jpg" else "img_${System.currentTimeMillis()}.jpg"
    ): OptimizedImage? {
        return try {
            val rawWidth = source.width
            val rawHeight = source.height
            if (rawWidth <= 0 || rawHeight <= 0) return null

            val maxWidth = if (isCover) 480 else 380
            val maxHeight = if (isCover) 800 else 380

            val scale = minOf(
                maxWidth.toFloat() / rawWidth,
                maxHeight.toFloat() / rawHeight,
                1.0f
            )
            val targetWidth = (rawWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (rawHeight * scale).roundToInt().coerceAtLeast(1)

            val scaledBitmap = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
            } else {
                source
            }

            // Grayscale conversion with E-Ink Contrast-Boost
            val grayscaleBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(grayscaleBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val satMatrix = ColorMatrix().apply { setSaturation(0f) }
            val contrast = 1.25f
            val brightness = -8f
            val contrastMatrix = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            ))
            satMatrix.postConcat(contrastMatrix)
            paint.colorFilter = ColorMatrixColorFilter(satMatrix)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

            val imagesDir = File(cacheDir, "epub_images").apply { mkdirs() }
            val uniqueLocalName = if (isCover) "cover_${System.currentTimeMillis()}.jpg" else fileName
            val targetFile = File(imagesDir, uniqueLocalName)

            FileOutputStream(targetFile).use { out ->
                grayscaleBitmap.compress(Bitmap.CompressFormat.JPEG, if (isCover) 75 else 60, out)
            }

            var colorCoverFile: File? = null
            if (isCover) {
                val colorFile = File(imagesDir, "color_cover_${System.nanoTime()}.jpg")
                FileOutputStream(colorFile).use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                colorCoverFile = colorFile
            }

            if (scaledBitmap != source) scaledBitmap.recycle()
            grayscaleBitmap.recycle()

            OptimizedImage(
                originalUrl = "pdf://first_page",
                localFile = targetFile,
                relativePath = "images/$fileName",
                width = targetWidth,
                height = targetHeight,
                isCover = isCover,
                colorCoverFile = colorCoverFile
            )
        } catch (e: Exception) {
            Log.e(tag, "Error optimizing bitmap for E-Ink", e)
            null
        }
    }
}
