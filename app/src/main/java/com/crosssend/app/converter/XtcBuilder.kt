package com.crosssend.app.converter

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class XtchInfo(
    val magic: String,
    val pageCount: Int,
    val width: Int,
    val height: Int,
    val title: String,
    val author: String
)

/**
 * Builds CrossPoint Reader 2.0 compatible 2-bit (4-level grayscale) .xtch containers
 * holding vertical-scan XTH pages.
 *
 * Binary layout (all multi-byte integers are Little-Endian):
 * - Header (56 bytes): "XTCH" (0x48435458), version 1.0, pageCount, flags, offsets
 * - Metadata (256 bytes at offset 56): title (128), author (64), publisher (32), language (16), timestamps/counts
 * - Page Index (pageCount * 16 bytes at offset 312): offset (u64), size (u32), width (u16), height (u16)
 * - Page Blobs: 22-byte "XTH\0" header + plane1 + plane2 (column-major right-to-left, 8 vertical pixels/byte)
 */
object XtcBuilder {

    const val MAGIC_XTCH = 0x48435458 // "XTCH"
    const val MAGIC_XTC = 0x00435458  // "XTC\0"
    const val MAGIC_XTH = 0x00485458  // "XTH\0"
    const val MAGIC_XTG = 0x00475458  // "XTG\0"

    const val HEADER_SIZE = 56
    const val METADATA_SIZE = 256
    const val PAGE_INDEX_ENTRY_SIZE = 16
    const val XTH_HEADER_SIZE = 22

    // XTH 2-bit gray encoding: (bit1 << 1) | bit2
    const val LEVEL_WHITE: Byte = 0      // 0b00
    const val LEVEL_DARK_GRAY: Byte = 1  // 0b01
    const val LEVEL_LIGHT_GRAY: Byte = 2 // 0b10
    const val LEVEL_BLACK: Byte = 3      // 0b11

    fun buildXtch(
        outputFile: File,
        title: String,
        author: String?,
        pages: List<ByteArray>,
        width: Int,
        height: Int,
        publisher: String = "CrossSend",
        language: String = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
    ): Boolean {
        if (pages.isEmpty() || width <= 0 || height <= 0) return false
        outputFile.parentFile?.mkdirs()
        return FileOutputStream(outputFile).use { fos ->
            BufferedOutputStream(fos, 32 * 1024).use { bos ->
                writeXtch(bos, title, author, pages, width, height, publisher, language)
            }
        }
    }

    fun writeXtch(
        output: OutputStream,
        title: String,
        author: String?,
        xthPages: List<ByteArray>,
        width: Int,
        height: Int,
        publisher: String = "CrossSend",
        language: String = "en-US",
        createTimeSec: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (xthPages.isEmpty() || width <= 0 || height <= 0) return false
        val pageCount = xthPages.size.coerceAtMost(0xFFFF)

        val metadataOffset = HEADER_SIZE.toLong()
        val indexOffset = metadataOffset + METADATA_SIZE
        val dataOffset = indexOffset + pageCount.toLong() * PAGE_INDEX_ENTRY_SIZE

        // 1. Write 56-byte XTCH header
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC_XTCH)
            put(1.toByte()) // versionMajor
            put(0.toByte()) // versionMinor
            putShort(pageCount.toShort())
            put(0.toByte()) // readDirection = 0 (L->R)
            put(1.toByte()) // hasMetadata = 1
            put(0.toByte()) // hasThumbnails = 0
            put(0.toByte()) // hasChapters = 0
            putInt(1)       // currentPage = 1
            putLong(metadataOffset)
            putLong(indexOffset)
            putLong(dataOffset)
            putLong(0L)     // thumbOffset
            putLong(0L)     // chapterOffset
        }.array()
        output.write(header)

        // 2. Write 256-byte Metadata block
        val metaBuf = ByteBuffer.allocate(METADATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        metaBuf.put(fixedUtf8Bytes(title, 128))
        metaBuf.put(fixedUtf8Bytes(author ?: "", 64))
        metaBuf.put(fixedUtf8Bytes(publisher, 32))
        metaBuf.put(fixedUtf8Bytes(language, 16))
        metaBuf.putInt((createTimeSec and 0xFFFFFFFFL).toInt())
        metaBuf.putShort(0) // coverPage
        metaBuf.putShort(0) // chapterCount
        metaBuf.put(ByteArray(8)) // reserved
        output.write(metaBuf.array())

        // 3. Write Page Index Table
        var runningOffset = dataOffset
        val indexEntryBuf = ByteBuffer.allocate(PAGE_INDEX_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until pageCount) {
            val pageBlob = xthPages[i]
            indexEntryBuf.clear()
            indexEntryBuf.putLong(runningOffset)
            indexEntryBuf.putInt(pageBlob.size)
            indexEntryBuf.putShort(width.toShort())
            indexEntryBuf.putShort(height.toShort())
            output.write(indexEntryBuf.array())
            runningOffset += pageBlob.size.toLong()
        }

        // 4. Write Page Data Blobs
        for (i in 0 until pageCount) {
            output.write(xthPages[i])
        }
        output.flush()
        return true
    }

    /**
     * Reusable per-document scratch buffers for [bitmapToXthPage] to avoid allocating
     * multiple multi-megabyte pixel and luminance arrays on every rendered page.
     */
    class XthScratchBuffers(initialPixelCount: Int = 0) {
        internal var pixels = IntArray(initialPixelCount)
        internal var luma = FloatArray(initialPixelCount)
        internal var work = FloatArray(initialPixelCount)
        internal var photoMask = BooleanArray(initialPixelCount)
        internal var levels = ByteArray(initialPixelCount)

        internal fun ensureCapacity(pixelCount: Int) {
            if (pixels.size < pixelCount) pixels = IntArray(pixelCount)
            if (luma.size < pixelCount) luma = FloatArray(pixelCount)
            if (work.size < pixelCount) work = FloatArray(pixelCount)
            if (photoMask.size < pixelCount) photoMask = BooleanArray(pixelCount)
            if (levels.size != pixelCount) levels = ByteArray(pixelCount)
        }
    }

    /**
     * Converts a Bitmap of exact dimensions (width x height) into an XTH 2-bit page blob.
     * Pixels inside [photoRects] use photo-preserving contrast and unrestricted 4-level Atkinson dithering,
     * while surrounding text and UI surfaces keep crisp black glyph cores and dither-free gray boxes.
     */
    fun bitmapToXthPage(
        bitmap: Bitmap,
        photoRects: List<Rect> = emptyList(),
        scratch: XthScratchBuffers? = null
    ): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixelCount = w * h
        scratch?.ensureCapacity(pixelCount)
        val pixels = scratch?.pixels ?: IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val photoMask = if (photoRects.isNotEmpty()) {
            val mask = scratch?.photoMask?.also { java.util.Arrays.fill(it, 0, pixelCount, false) }
                ?: BooleanArray(pixelCount)
            for (rect in photoRects) {
                val left = rect.left.coerceIn(0, w)
                val right = rect.right.coerceIn(0, w)
                val top = rect.top.coerceIn(0, h)
                val bottom = rect.bottom.coerceIn(0, h)
                for (py in top until bottom) {
                    val rowOffset = py * w
                    for (px in left until right) {
                        mask[rowOffset + px] = true
                    }
                }
            }
            mask
        } else null

        return pixelsToXthPage(
            pixels = pixels,
            width = w,
            height = h,
            photoMask = photoMask,
            scratch = scratch
        )
    }

    internal fun pixelsToXthPage(
        pixels: IntArray,
        width: Int,
        height: Int,
        photoMask: BooleanArray? = null,
        scratch: XthScratchBuffers? = null
    ): ByteArray {
        val levels = pixelsToLevels(
            pixels = pixels,
            width = width,
            height = height,
            photoMask = photoMask,
            scratch = scratch
        )
        return encodeXthPage(width, height, levels)
    }

    internal fun pixelsToLevels(
        pixels: IntArray,
        width: Int,
        height: Int,
        photoMask: BooleanArray? = null,
        scratch: XthScratchBuffers? = null
    ): ByteArray {
        val pixelCount = width * height
        require(pixels.size >= pixelCount) {
            "Expected at least $pixelCount pixels, got ${pixels.size}"
        }
        scratch?.ensureCapacity(pixelCount)
        val luma = scratch?.luma ?: FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // ITU-R BT.601 luminance + gentle S-contrast curve for E-Ink web crispness
            val rawY = 0.299f * r + 0.587f * g + 0.114f * b
            luma[i] = if (photoMask != null && photoMask[i]) {
                applyPhotoEInkContrast(rawY)
            } else {
                applyEInkContrast(rawY)
            }
        }
        return ditherAtkinson4Level(
            luminance = luma,
            width = width,
            height = height,
            photoMask = photoMask,
            scratchWork = scratch?.work,
            scratchOut = scratch?.levels
        )
    }

    internal fun applyPhotoEInkContrast(y: Float): Float {
        // Preserve full tonal gradient & shadow detail inside photos/illustrations for Atkinson dithering
        if (y >= 242f) return 255f
        if (y <= 28f) return 0f
        return ((y - 128f) * 1.15f + 128f).coerceIn(0f, 255f)
    }

    internal fun applyEInkContrast(y: Float): Float {
        // Snap near-white paper backgrounds cleanly to pure 255 and deep text/glyphs to pure 0
        if (y >= 240f) return 255f
        if (y <= 68f) return 0f
        // Preserve native E-Ink hardware gray levels (LEVEL_LIGHT_GRAY=170, LEVEL_DARK_GRAY=85) for dither-free UI surfaces
        if (y in 168f..172f) return 170f
        if (y in 83f..87f) return 85f
        return ((y - 128f) * 1.15f + 128f).coerceIn(0f, 255f)
    }

    /**
     * Applies Atkinson error diffusion into 4 target levels:
     * 0 = White (255), 2 = Light Gray (170), 1 = Dark Gray (85), 3 = Black (0).
     */
    fun ditherAtkinson4Level(
        luminance: FloatArray,
        width: Int,
        height: Int,
        photoMask: BooleanArray? = null,
        scratchWork: FloatArray? = null,
        scratchOut: ByteArray? = null
    ): ByteArray {
        val pixelCount = width * height
        val work = if (scratchWork != null && scratchWork.size >= pixelCount) {
            System.arraycopy(luminance, 0, scratchWork, 0, pixelCount)
            scratchWork
        } else {
            luminance.copyOf(pixelCount)
        }
        val out = if (scratchOut != null && scratchOut.size == pixelCount) {
            scratchOut
        } else {
            ByteArray(pixelCount)
        }

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                val oldVal = work[idx].coerceIn(0f, 255f)
                val (newVal, level) = when {
                    oldVal >= 212.5f -> 255f to LEVEL_WHITE
                    oldVal >= 127.5f -> 170f to LEVEL_LIGHT_GRAY
                    oldVal >= 42.5f -> 85f to LEVEL_DARK_GRAY
                    else -> 0f to LEVEL_BLACK
                }
                out[idx] = level

                // Suppress error diffusion on antialiased glyph edges next to clean white/light-gray backgrounds
                // (never inside photos/images, where full Atkinson error diffusion produces smooth halftones).
                val isPhotoPixel = photoMask != null && photoMask[idx]
                if (!isPhotoPixel) {
                    val rawCenter = luminance[idx]
                    if (rawCenter in 69f..237f && isTextToBackgroundEdge(luminance, width, height, x, y)) {
                        continue
                    }
                }

                val err = (oldVal - newVal) / 8f
                if (err == 0f) continue

                if (x + 1 < width) work[idx + 1] += err
                if (x + 2 < width) work[idx + 2] += err
                if (y + 1 < height) {
                    val nextRow = idx + width
                    if (x - 1 >= 0) work[nextRow - 1] += err
                    work[nextRow] += err
                    if (x + 1 < width) work[nextRow + 1] += err
                }
                if (y + 2 < height) {
                    work[idx + 2 * width] += err
                }
            }
        }
        return out
    }

    private fun isTextToBackgroundEdge(
        luminance: FloatArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int
    ): Boolean {
        var hasBlack = false
        var hasCleanBg = false
        val yMin = maxOf(0, cy - 1)
        val yMax = minOf(height - 1, cy + 1)
        val xMin = maxOf(0, cx - 1)
        val xMax = minOf(width - 1, cx + 1)
        for (ny in yMin..yMax) {
            val row = ny * width
            for (nx in xMin..xMax) {
                val v = luminance[row + nx]
                if (v <= 68f) {
                    hasBlack = true
                } else if (v >= 238f || (v in 168f..172f)) {
                    hasCleanBg = true
                }
                if (hasBlack && hasCleanBg) return true
            }
        }
        return false
    }

    /**
     * Encodes quantized levels (row-major, values 0..3) into a complete 22-byte header + dual vertical bitplane XTH blob.
     */
    fun encodeXthPage(width: Int, height: Int, quantizedLevels: ByteArray): ByteArray {
        require(quantizedLevels.size == width * height) {
            "Expected ${width * height} pixels, got ${quantizedLevels.size}"
        }
        val colBytes = (height + 7) / 8
        val planeSize = colBytes * width
        val dataSize = planeSize * 2

        val blob = ByteArray(XTH_HEADER_SIZE + dataSize)
        val headerBuf = ByteBuffer.wrap(blob, 0, XTH_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.putInt(MAGIC_XTH)
        headerBuf.putShort(width.toShort())
        headerBuf.putShort(height.toShort())
        headerBuf.put(0.toByte()) // colorMode
        headerBuf.put(0.toByte()) // compression
        headerBuf.putInt(dataSize)
        headerBuf.putLong(0L) // md5 optional

        val plane1Offset = XTH_HEADER_SIZE
        val plane2Offset = XTH_HEADER_SIZE + planeSize

        for (colIdx in 0 until width) {
            val x = width - 1 - colIdx
            val colByteBase = colIdx * colBytes
            for (y in 0 until height) {
                val level = quantizedLevels[y * width + x].toInt() and 0x03
                val bit1 = (level shr 1) and 1
                val bit2 = level and 1
                val bytePos = colByteBase + (y shr 3)
                val bitMask = 1 shl (7 - (y and 7))
                if (bit1 != 0) {
                    blob[plane1Offset + bytePos] = (blob[plane1Offset + bytePos].toInt() or bitMask).toByte()
                }
                if (bit2 != 0) {
                    blob[plane2Offset + bytePos] = (blob[plane2Offset + bytePos].toInt() or bitMask).toByte()
                }
            }
        }
        return blob
    }

    /**
     * Overwrites a horizontal strip (`[stripTopY, stripTopY + stripHeight)`) inside an already-encoded
     * XTH page blob with new quantized levels (values 0..3), leaving all other rows untouched.
     */
    internal fun overlayStripLevelsOnXthPage(
        xthBlob: ByteArray,
        width: Int,
        height: Int,
        stripTopY: Int,
        stripHeight: Int,
        stripLevels: ByteArray
    ) {
        if (width <= 0 || height <= 0 || stripHeight <= 0) return
        val colBytes = (height + 7) / 8
        val planeSize = colBytes * width
        if (xthBlob.size < XTH_HEADER_SIZE + planeSize * 2) return
        if (stripLevels.size < width * stripHeight) return

        val yStart = stripTopY.coerceAtLeast(0)
        val yEnd = minOf(height, stripTopY + stripHeight)
        if (yEnd <= yStart) return

        val plane1Offset = XTH_HEADER_SIZE
        val plane2Offset = XTH_HEADER_SIZE + planeSize

        for (colIdx in 0 until width) {
            val x = width - 1 - colIdx
            val colByteBase = colIdx * colBytes
            for (y in yStart until yEnd) {
                val stripY = y - stripTopY
                val level = stripLevels[stripY * width + x].toInt() and 0x03
                val bit1 = (level shr 1) and 1
                val bit2 = level and 1
                val bytePos = colByteBase + (y shr 3)
                val bitMask = 1 shl (7 - (y and 7))
                val clearMask = bitMask.inv()

                val p1 = (xthBlob[plane1Offset + bytePos].toInt() and 0xFF) and clearMask
                xthBlob[plane1Offset + bytePos] = (if (bit1 != 0) p1 or bitMask else p1).toByte()

                val p2 = (xthBlob[plane2Offset + bytePos].toInt() and 0xFF) and clearMask
                xthBlob[plane2Offset + bytePos] = (if (bit2 != 0) p2 or bitMask else p2).toByte()
            }
        }
    }

    fun readXtchInfo(file: File): XtchInfo? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magicInt = buf.getInt(0)
                if (magicInt != MAGIC_XTCH && magicInt != MAGIC_XTC) return null
                val magicStr = if (magicInt == MAGIC_XTCH) "XTCH" else "XTC"
                val pageCount = buf.getShort(6).toInt() and 0xFFFF
                val hasMetadata = (header[9].toInt() and 0xFF) != 0
                val metadataOffset = buf.getLong(16)
                val indexOffset = buf.getLong(24)

                var title = ""
                var author = ""
                if (hasMetadata && metadataOffset >= HEADER_SIZE && file.length() >= metadataOffset + METADATA_SIZE) {
                    raf.seek(metadataOffset)
                    val meta = ByteArray(METADATA_SIZE)
                    raf.readFully(meta)
                    title = readNullTerminatedUtf8(meta, 0, 128)
                    author = readNullTerminatedUtf8(meta, 128, 64)
                }

                var w = 480
                var h = 800
                if (pageCount > 0 && file.length() >= indexOffset + PAGE_INDEX_ENTRY_SIZE) {
                    raf.seek(indexOffset)
                    val idxEntry = ByteArray(PAGE_INDEX_ENTRY_SIZE)
                    raf.readFully(idxEntry)
                    val idxBuf = ByteBuffer.wrap(idxEntry).order(ByteOrder.LITTLE_ENDIAN)
                    w = idxBuf.getShort(12).toInt() and 0xFFFF
                    h = idxBuf.getShort(14).toInt() and 0xFFFF
                }
                XtchInfo(magicStr, pageCount, w, h, title, author)
            }
        } catch (_: Exception) {
            null
        }
    }

    data class DecodedXthPage(
        val width: Int,
        val height: Int,
        val argbPixels: IntArray
    )

    /**
     * Decodes a zero-based page of an `.xtch` file into ARGB pixel data (or null if invalid/out of range).
     */
    fun decodePagePixels(file: File, pageIndex: Int = 0): DecodedXthPage? {
        if (pageIndex < 0 || !file.exists() || file.length() < HEADER_SIZE + PAGE_INDEX_ENTRY_SIZE) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val containerMagic = buf.getInt(0)
                if (containerMagic != MAGIC_XTCH && containerMagic != MAGIC_XTC) return null
                val pageCount = buf.getShort(6).toInt() and 0xFFFF
                if (pageIndex >= pageCount) return null
                val indexOffset = buf.getLong(24)
                val entryOffset = indexOffset + pageIndex.toLong() * PAGE_INDEX_ENTRY_SIZE
                if (entryOffset < HEADER_SIZE || entryOffset + PAGE_INDEX_ENTRY_SIZE > file.length()) return null

                raf.seek(entryOffset)
                val entry = ByteArray(PAGE_INDEX_ENTRY_SIZE)
                raf.readFully(entry)
                val entryBuf = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
                val pageOffset = entryBuf.getLong(0)
                val pageSize = entryBuf.getInt(8)
                if (pageSize < XTH_HEADER_SIZE || pageOffset + pageSize > file.length() || pageSize > 4_000_000) return null

                raf.seek(pageOffset)
                val xthHeader = ByteArray(XTH_HEADER_SIZE)
                raf.readFully(xthHeader)
                val xthBuf = ByteBuffer.wrap(xthHeader).order(ByteOrder.LITTLE_ENDIAN)
                val pageMagic = xthBuf.getInt(0)
                val width = xthBuf.getShort(4).toInt() and 0xFFFF
                val height = xthBuf.getShort(6).toInt() and 0xFFFF
                val dataSize = xthBuf.getInt(10)

                when (pageMagic) {
                    MAGIC_XTH -> {
                        val colBytes = (height + 7) / 8
                        val planeSize = colBytes * width
                        if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || dataSize < planeSize * 2) return null

                        val planes = ByteArray(planeSize * 2)
                        raf.readFully(planes)

                        val pixels = IntArray(width * height)
                        val palette = intArrayOf(
                            0xFFFFFFFF.toInt(), // 00: White
                            0xFF555555.toInt(), // 01: Dark Gray
                            0xFFAAAAAA.toInt(), // 10: Light Gray
                            0xFF000000.toInt()  // 11: Black
                        )

                        for (colIdx in 0 until width) {
                            val x = width - 1 - colIdx
                            val colByteBase = colIdx * colBytes
                            for (y in 0 until height) {
                                val bytePos = colByteBase + (y shr 3)
                                val shift = 7 - (y and 7)
                                val b1 = (planes[bytePos].toInt() shr shift) and 1
                                val b2 = (planes[planeSize + bytePos].toInt() shr shift) and 1
                                val level = (b1 shl 1) or b2
                                pixels[y * width + x] = palette[level]
                            }
                        }
                        DecodedXthPage(width = width, height = height, argbPixels = pixels)
                    }
                    MAGIC_XTG -> {
                        val rowBytes = (width + 7) / 8
                        val expectedBytes = rowBytes * height
                        if (width <= 0 || height <= 0 || width > 2048 || height > 2048 || dataSize < expectedBytes) return null

                        val mono = ByteArray(expectedBytes)
                        raf.readFully(mono)

                        val pixels = IntArray(width * height)
                        for (y in 0 until height) {
                            val rowBase = y * rowBytes
                            for (x in 0 until width) {
                                val byteVal = mono[rowBase + (x shr 3)].toInt() and 0xFF
                                val bit = (byteVal shr (7 - (x and 7))) and 1
                                pixels[y * width + x] = if (bit == 1) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                            }
                        }
                        DecodedXthPage(width = width, height = height, argbPixels = pixels)
                    }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a zero-based page of an .xtch file into a preview Bitmap (or null if invalid/out of range).
     */
    fun decodePageBitmap(file: File, pageIndex: Int = 0): Bitmap? {
        val decoded = decodePagePixels(file, pageIndex) ?: return null
        return try {
            Bitmap.createBitmap(decoded.argbPixels, decoded.width, decoded.height, Bitmap.Config.RGB_565)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes Page 1 of an .xtch file into a preview Bitmap (or null if invalid).
     */
    fun decodeFirstPageBitmap(file: File): Bitmap? = decodePageBitmap(file, pageIndex = 0)

    private fun fixedUtf8Bytes(text: String, maxBytes: Int): ByteArray {
        val out = ByteArray(maxBytes)
        val raw = text.toByteArray(Charsets.UTF_8)
        val copyLen = minOf(raw.size, maxBytes - 1) // keep trailing null terminator
        if (copyLen > 0) {
            System.arraycopy(raw, 0, out, 0, copyLen)
        }
        return out
    }

    private fun readNullTerminatedUtf8(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = minOf(buf.size, offset + len)
        while (end < limit && buf[end] != 0.toByte()) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }
}
