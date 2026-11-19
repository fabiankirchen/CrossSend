package com.crosssend.app.converter

import com.crosssend.app.data.local.DomainHostUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XtcBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `normalizeHost strips www and lowercases safely`() {
        assertEquals("faz.net", DomainHostUtils.normalizeHost("https://www.faz.net/aktuell/politik/artikel.html"))
        assertEquals("sub.example.com", DomainHostUtils.normalizeHost("https://SUB.Example.Com:8443/path?a=1"))
        assertNull(DomainHostUtils.normalizeHost("not-a-url"))
        assertNull(DomainHostUtils.normalizeHost(null))
    }

    @Test
    fun `encodeXthPage packs column-major right-to-left 2-bit planes accurately`() {
        val width = 8
        val height = 8
        // Fill all pixels with BLACK (3 -> bit1=1, bit2=1) except top-right pixel (x=7, y=0) = WHITE (0)
        // and (x=7, y=1) = LIGHT_GRAY (2 -> bit1=1, bit2=0), (x=7, y=2) = DARK_GRAY (1 -> bit1=0, bit2=1)
        val levels = ByteArray(width * height) { XtcBuilder.LEVEL_BLACK }
        levels[0 * width + 7] = XtcBuilder.LEVEL_WHITE
        levels[1 * width + 7] = XtcBuilder.LEVEL_LIGHT_GRAY
        levels[2 * width + 7] = XtcBuilder.LEVEL_DARK_GRAY

        val blob = XtcBuilder.encodeXthPage(width, height, levels)
        val expectedDataSize = ((height + 7) / 8) * width * 2
        assertEquals(XtcBuilder.XTH_HEADER_SIZE + expectedDataSize, blob.size)

        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(XtcBuilder.MAGIC_XTH, buf.getInt(0))
        assertEquals(width, buf.getShort(4).toInt() and 0xFFFF)
        assertEquals(height, buf.getShort(6).toInt() and 0xFFFF)
        assertEquals(expectedDataSize, buf.getInt(10))

        // First byte of plane1 represents x = width - 1 = 7, rows y=0..7 (MSB = y=0)
        // y=0 is WHITE (bit1=0), y=1 is LIGHT_GRAY (bit1=1), y=2 is DARK_GRAY (bit1=0), y=3..7 BLACK (bit1=1)
        // Expected bits: 0 1 0 1 1 1 1 1 = 0x5F
        val plane1Byte0 = blob[XtcBuilder.XTH_HEADER_SIZE].toInt() and 0xFF
        assertEquals(0b0101_1111, plane1Byte0)

        // Plane2 at same column x=7: y=0(0), y=1(0), y=2(1), y=3..7(1) -> 0011_1111 = 0x3F
        val plane2Byte0 = blob[XtcBuilder.XTH_HEADER_SIZE + width].toInt() and 0xFF
        assertEquals(0b0011_1111, plane2Byte0)
    }

    @Test
    fun `buildXtch writes valid header, metadata, and page index`() {
        val w = 480
        val h = 800
        val pageLevels = ByteArray(w * h) { XtcBuilder.LEVEL_WHITE }
        val page1 = XtcBuilder.encodeXthPage(w, h, pageLevels)
        val page2 = XtcBuilder.encodeXthPage(w, h, pageLevels)

        val outFile = tempFolder.newFile("sample_article.xtch")
        val ok = XtcBuilder.buildXtch(
            outputFile = outFile,
            title = "CrossPoint Original Layout Test",
            author = "CrossSend",
            pages = listOf(page1, page2),
            width = w,
            height = h
        )
        assertTrue(ok)

        val info = XtcBuilder.readXtchInfo(outFile)
        assertNotNull(info)
        assertEquals("XTCH", info?.magic)
        assertEquals(2, info?.pageCount)
        assertEquals(w, info?.width)
        assertEquals(h, info?.height)
        assertEquals("CrossPoint Original Layout Test", info?.title)
        assertEquals("CrossSend", info?.author)
    }

    @Test
    fun `decodePagePixels decodes multi-page XTCH pages accurately and bounds-checks`() {
        val w = 16
        val h = 16
        val page0Levels = ByteArray(w * h) { XtcBuilder.LEVEL_WHITE }
        page0Levels[0] = XtcBuilder.LEVEL_BLACK
        val page1Levels = ByteArray(w * h) { XtcBuilder.LEVEL_DARK_GRAY }
        page1Levels[h * w - 1] = XtcBuilder.LEVEL_LIGHT_GRAY

        val outFile = tempFolder.newFile("multipage.xtch")
        assertTrue(
            XtcBuilder.buildXtch(
                outputFile = outFile,
                title = "Multi Page",
                author = "CrossSend",
                pages = listOf(
                    XtcBuilder.encodeXthPage(w, h, page0Levels),
                    XtcBuilder.encodeXthPage(w, h, page1Levels)
                ),
                width = w,
                height = h
            )
        )

        val decoded0 = XtcBuilder.decodePagePixels(outFile, 0)
        assertNotNull(decoded0)
        assertEquals(0xFF000000.toInt(), decoded0!!.argbPixels[0])
        assertEquals(0xFFFFFFFF.toInt(), decoded0.argbPixels[1])

        val decoded1 = XtcBuilder.decodePagePixels(outFile, 1)
        assertNotNull(decoded1)
        assertEquals(0xFF555555.toInt(), decoded1!!.argbPixels[0])
        assertEquals(0xFFAAAAAA.toInt(), decoded1.argbPixels[w * h - 1])

        assertNull(XtcBuilder.decodePagePixels(outFile, -1))
        assertNull(XtcBuilder.decodePagePixels(outFile, 2))
    }

    @Test
    fun `decodePagePixels decodes 1-bit XTC XTG container accurately`() {
        val w = 8
        val h = 8
        val rowBytes = 1
        val monoData = ByteArray(rowBytes * h) { 0xFF.toByte() } // all white
        // Top row (y=0): pixel x=0 black (bit 7 = 0), pixels x=1..7 white (bits 6..0 = 1) -> 0x7F
        monoData[0] = 0x7F.toByte()
        // Bottom row (y=7): pixel x=7 black (bit 0 = 0), pixels x=0..6 white -> 0xFE
        monoData[7] = 0xFE.toByte()

        val xtgBlob = ByteBuffer.allocate(XtcBuilder.XTH_HEADER_SIZE + monoData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(XtcBuilder.MAGIC_XTG)
                putShort(w.toShort())
                putShort(h.toShort())
                put(0.toByte())
                put(0.toByte())
                putInt(monoData.size)
                putLong(0L)
                put(monoData)
            }.array()

        val dataOffset = (XtcBuilder.HEADER_SIZE + XtcBuilder.PAGE_INDEX_ENTRY_SIZE).toLong()
        val xtcBytes = java.io.ByteArrayOutputStream().apply {
            write(
                ByteBuffer.allocate(XtcBuilder.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(XtcBuilder.MAGIC_XTC)
                    put(1.toByte())
                    put(0.toByte())
                    putShort(1.toShort())
                    put(0.toByte())
                    put(0.toByte()) // hasMetadata = 0
                    put(0.toByte())
                    put(0.toByte())
                    putInt(1)
                    putLong(0L)
                    putLong(XtcBuilder.HEADER_SIZE.toLong())
                    putLong(dataOffset)
                    putLong(0L)
                    putLong(0L)
                }.array()
            )
            write(
                ByteBuffer.allocate(XtcBuilder.PAGE_INDEX_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putLong(dataOffset)
                    putInt(xtgBlob.size)
                    putShort(w.toShort())
                    putShort(h.toShort())
                }.array()
            )
            write(xtgBlob)
        }.toByteArray()

        val outFile = tempFolder.newFile("sample_mono.xtc")
        outFile.writeBytes(xtcBytes)

        val info = XtcBuilder.readXtchInfo(outFile)
        assertNotNull(info)
        assertEquals("XTC", info?.magic)
        assertEquals(1, info?.pageCount)

        val decoded = XtcBuilder.decodePagePixels(outFile, 0)
        assertNotNull(decoded)
        assertEquals(w, decoded!!.width)
        assertEquals(h, decoded.height)
        assertEquals(0xFF000000.toInt(), decoded.argbPixels[0]) // (0, 0) black
        assertEquals(0xFFFFFFFF.toInt(), decoded.argbPixels[1]) // (1, 0) white
        assertEquals(0xFF000000.toInt(), decoded.argbPixels[7 * w + 7]) // (7, 7) black
    }

    @Test
    fun `computeSmartPageCuts keeps large boxes and images intact without 72 percent fallthrough`() {
        val pageH = 800
        // Large info box / image spanning [420..920] (height 500px <= 0.90*pageH = 720px)
        // Old 0.72 minAdvance (576px) rejected cut=416 and sliced straight through 800!
        val boxes = listOf(
            LayoutBox(20, 44, isKeepBlock = false),
            LayoutBox(52, 76, isKeepBlock = false),
            LayoutBox(420, 920, isKeepBlock = true),
            LayoutBox(950, 980, isKeepBlock = false)
        )
        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 1400,
            pageHeight = pageH,
            firstContentTop = 20,
            rawBoxes = boxes
        )
        assertEquals(2, cuts.size)
        // Page 1 must break before [420..920]
        assertTrue("Expected Page 1 end <= 420, got ${cuts[0].second}", cuts[0].second <= 420)
        // Page 2 starts cleanly above 420 and encloses the whole 920 bottom cleanly
        assertTrue("Expected Page 2 start <= 420, got ${cuts[1].first}", cuts[1].first <= 420)
        assertTrue("Expected Page 2 end >= 920, got ${cuts[1].second}", cuts[1].second >= 920)
    }

    @Test
    fun `computeSmartPageCuts converges across cascading overlapping boxes and trims blank tail`() {
        val pageH = 800
        // Box B [700..765] precedes Box A [755..820]; idealEnd=790.
        // Moving above Box A -> 751 slices Box B unless fixed-point pass also moves above Box B -> 696!
        val boxes = listOf(
            LayoutBox(700, 765, isKeepBlock = true),
            LayoutBox(755, 820, isKeepBlock = true),
            LayoutBox(830, 860, isKeepBlock = false)
        )
        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 3200, // simulates huge empty footer margin below y=860
            pageHeight = pageH,
            firstContentTop = 0,
            rawBoxes = boxes
        )
        // Trimmed to contentBottom (~880) -> only 2 pages instead of 4 blank tail pages!
        assertEquals(2, cuts.size)
        assertTrue("Expected Page 1 end <= 700 (not slicing Box B), got ${cuts[0].second}", cuts[0].second <= 700)
        assertTrue("Expected Page 2 to contain Box B & Box A whole", cuts[1].first <= 700 && cuts[1].second >= 860)
    }

    @Test
    fun `computeSmartPageCuts avoids backward domino cascade on tight leading text lines`() {
        val pageH = 600
        // Simulate 35 tight consecutive text lines (step=18px, height=21px -> 3px overlap between adjacent lines)
        val boxes = (0 until 35).map { idx ->
            val top = 20 + idx * 18
            LayoutBox(top = top, bottom = top + 21, isKeepBlock = false)
        }
        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 700,
            pageHeight = pageH,
            firstContentTop = 20,
            rawBoxes = boxes
        )
        assertEquals(2, cuts.size)
        // Page 1 must use almost the entire 600px viewport instead of cascading backward 250+ px!
        val p1Height = cuts[0].second - cuts[0].first
        assertTrue("Expected Page 1 height >= 540px without backward line cascade, got $p1Height", p1Height >= 540)
    }

    @Test
    fun `computeSmartPageCuts moves dangling bottom heading to next page with its lead without half-empty page`() {
        val pageH = 576
        // Dense body lines down to 516, followed by heading [528..556] + glued heading+lead [528..612]
        val textLines = (0 until 22).map { idx ->
            val top = 20 + idx * 22
            LayoutBox(top = top, bottom = top + 20, isKeepBlock = false) // ends at 20 + 21*22 + 20 = 502
        }
        val boxes = textLines + listOf(
            LayoutBox(528, 556, isKeepBlock = true), // heading alone
            LayoutBox(528, 612, isKeepBlock = true), // heading + following lead block
            LayoutBox(570, 592, isKeepBlock = false),
            LayoutBox(596, 618, isKeepBlock = false)
        )
        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 760,
            pageHeight = pageH,
            firstContentTop = 20,
            rawBoxes = boxes
        )
        assertEquals(2, cuts.size)
        // Page 1 must break cleanly before heading [528..612], keeping ~500px full on Page 1
        assertTrue("Expected Page 1 end <= 528, got ${cuts[0].second}", cuts[0].second <= 528)
        assertTrue("Expected Page 1 height >= 480px (no half-empty page), got ${cuts[0].second - cuts[0].first}", cuts[0].second - cuts[0].first >= 480)
        // Page 2 starts with heading and covers heading + lead
        assertTrue("Expected Page 2 start <= 528, got ${cuts[1].first}", cuts[1].first <= 528)
        assertTrue("Expected Page 2 end >= 618, got ${cuts[1].second}", cuts[1].second >= 618)
    }

    @Test
    fun `matchingBgBandsForEdge bleeds continuing multi-page section backgrounds and skips new cards`() {
        val pageH = 576
        val layers = listOf(
            BgLayer(top = 0, bottom = 5000, left = 0, right = 360, isRoot = true),
            BgLayer(top = 600, bottom = 1800, left = 0, right = 360, isRoot = false), // spans pages
            BgLayer(top = 900, bottom = 1500, left = 16, right = 344, isRoot = false), // inset callout crossing 1200
            BgLayer(top = 1850, bottom = 2100, left = 16, right = 344, isRoot = false) // short box starting at 1850
        )

        // At cutY=1200 (middle of [600..1800] & [900..1500]): full-width layer collapsed + inset layer preserved
        val midTop = WebPageXtchRenderer.matchingBgBandsForEdge(1200, isTopEdge = true, pageH, layers)
        assertEquals(2, midTop.size)
        assertEquals(600, midTop[0].top)
        assertEquals(16, midTop[1].left)

        // At cutY=600 top edge (start of multi-page section >= 1.15 * pageH): section matches top edge
        val startTop = WebPageXtchRenderer.matchingBgBandsForEdge(598, isTopEdge = true, pageH, layers)
        assertEquals(1, startTop.size)
        assertEquals(600, startTop[0].top)

        // At cutY=1850 top edge (start of short box < 1.15 * pageH): only root matches, short box does NOT bleed up
        val shortTop = WebPageXtchRenderer.matchingBgBandsForEdge(1848, isTopEdge = true, pageH, layers)
        assertEquals(1, shortTop.size)
        assertTrue(shortTop[0].isRoot)
    }

    @Test
    fun `applyEInkContrast snaps dark text to pure black, subtle tints to white, and preserves native gray steps`() {
        assertEquals(0f, XtcBuilder.applyEInkContrast(0f), 0.01f)
        assertEquals(0f, XtcBuilder.applyEInkContrast(68f), 0.01f)
        assertEquals(85f, XtcBuilder.applyEInkContrast(85f), 0.01f)
        assertEquals(170f, XtcBuilder.applyEInkContrast(170f), 0.01f)
        assertEquals(255f, XtcBuilder.applyEInkContrast(240f), 0.01f)
        assertEquals(255f, XtcBuilder.applyEInkContrast(255f), 0.01f)

        val midtone = XtcBuilder.applyEInkContrast(110f)
        assertTrue("Expected continuous midtone contrast between 85 and 128, got $midtone", midtone in 90f..120f)
    }

    @Test
    fun `ditherAtkinson4Level keeps glyph edges antialiased without bleeding dither dots into paper or gray boxes`() {
        val w = 9
        val h = 7
        // White paper (255f) with a vertical black stroke at x=2 (0f) and antialiased edge at x=3 (100f / 175f)
        val paperLuma = FloatArray(w * h) { 255f }
        for (y in 1 until h - 1) {
            paperLuma[y * w + 2] = 0f
            paperLuma[y * w + 3] = if (y % 2 == 0) 100f else 175f
        }
        val paperLevels = XtcBuilder.ditherAtkinson4Level(paperLuma, w, h)
        for (y in 1 until h - 1) {
            assertEquals(XtcBuilder.LEVEL_BLACK, paperLevels[y * w + 2])
            val expectedEdge = if (y % 2 == 0) XtcBuilder.LEVEL_DARK_GRAY else XtcBuilder.LEVEL_LIGHT_GRAY
            assertEquals(expectedEdge, paperLevels[y * w + 3])
            // Surrounding white paper must have zero dithered gray dots
            for (x in 4 until w) {
                assertEquals("Unexpected dither bleed at ($x, $y)", XtcBuilder.LEVEL_WHITE, paperLevels[y * w + x])
            }
        }

        // Smooth #AAAAAA box (170f) with black stroke at x=2 (0f) and dark-gray AA edge at x=3 (95f)
        val boxLuma = FloatArray(w * h) { 170f }
        for (y in 1 until h - 1) {
            boxLuma[y * w + 2] = 0f
            boxLuma[y * w + 3] = 95f
        }
        val boxLevels = XtcBuilder.ditherAtkinson4Level(boxLuma, w, h)
        for (y in 1 until h - 1) {
            assertEquals(XtcBuilder.LEVEL_BLACK, boxLevels[y * w + 2])
            assertEquals(XtcBuilder.LEVEL_DARK_GRAY, boxLevels[y * w + 3])
            for (x in 4 until w) {
                assertEquals("Unexpected dither bleed in gray box at ($x, $y)", XtcBuilder.LEVEL_LIGHT_GRAY, boxLevels[y * w + x])
            }
        }
    }

    @Test
    fun `ditherAtkinson4Level preserves multi-level dithering across continuous photo gradients`() {
        val w = 16
        val h = 8
        val gradient = FloatArray(w * h) { idx ->
            val x = idx % w
            // Smooth gradient between 135 and 215 (between LEVEL_LIGHT_GRAY and LEVEL_WHITE)
            135f + (x / (w - 1f)) * 80f
        }
        val levels = XtcBuilder.ditherAtkinson4Level(gradient, w, h)
        val distinct = levels.toSet()
        assertTrue("Expected dithering across light gray and white in gradient, got $distinct",
            distinct.contains(XtcBuilder.LEVEL_LIGHT_GRAY) && distinct.contains(XtcBuilder.LEVEL_WHITE))
    }

    @Test
    fun `applyPhotoEInkContrast preserves dark shadow tones for dithering while applyEInkContrast keeps text black`() {
        // Dark photo shadow (luma 50) must NOT be crushed to 0f inside photos, unlike text (<= 68f -> 0f)
        assertEquals(0f, XtcBuilder.applyEInkContrast(50f), 0.01f)
        val photoShadow = XtcBuilder.applyPhotoEInkContrast(50f)
        assertTrue("Expected photo shadow tonal detail > 30f, got $photoShadow", photoShadow in 30f..50f)

        // With photoMask=true, even high-contrast edges inside a photo propagate dithering error normally
        val w = 6
        val h = 4
        val luma = FloatArray(w * h) { 255f }
        for (y in 0 until h) {
            luma[y * w + 0] = 0f
            luma[y * w + 1] = 200f
        }
        val photoMask = BooleanArray(w * h) { true }
        val photoLevels = XtcBuilder.ditherAtkinson4Level(luma, w, h, photoMask)
        assertTrue("Expected unrestricted Atkinson error diffusion inside photoMask", photoLevels.any { it == XtcBuilder.LEVEL_LIGHT_GRAY })
    }

    @Test
    fun `XthScratchBuffers reused across multiple pages produces byte-for-byte identical XTH output`() {
        val w = 24
        val h = 16
        val pixelCount = w * h

        val page1Pixels = IntArray(pixelCount) { idx ->
            val x = idx % w
            val y = idx / w
            val v = ((x * 11 + y * 13) % 256)
            (0xFF shl 24) or (v shl 16) or (((v * 3) % 256) shl 8) or ((255 - v) % 256)
        }
        val page1PhotoMask = BooleanArray(pixelCount) { idx ->
            val x = idx % w
            val y = idx / w
            x in 4 until 16 && y in 2 until 12
        }

        val page2Pixels = IntArray(pixelCount) { idx ->
            val x = idx % w
            val y = idx / w
            when {
                x == 6 && y in 2..12 -> 0xFF000000.toInt()
                x == 7 && y in 2..12 -> 0xFF909090.toInt()
                y in 0..3 -> 0xFFAAAAAA.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }

        val smallW = 16
        val smallH = 8
        val page3Pixels = IntArray(smallW * smallH) { idx ->
            val v = (idx * 7) % 256
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val expected1 = XtcBuilder.pixelsToXthPage(page1Pixels, w, h, page1PhotoMask)
        val expected2 = XtcBuilder.pixelsToXthPage(page2Pixels, w, h, photoMask = null)
        val expected3 = XtcBuilder.pixelsToXthPage(page3Pixels, smallW, smallH, photoMask = null)

        val scratch = XtcBuilder.XthScratchBuffers(pixelCount)
        val actual1 = XtcBuilder.pixelsToXthPage(page1Pixels, w, h, page1PhotoMask, scratch)
        val actual2 = XtcBuilder.pixelsToXthPage(page2Pixels, w, h, photoMask = null, scratch = scratch)
        val actual3 = XtcBuilder.pixelsToXthPage(page3Pixels, smallW, smallH, photoMask = null, scratch = scratch)

        assertArrayEquals(expected1, actual1)
        assertArrayEquals(expected2, actual2)
        assertArrayEquals(expected3, actual3)
    }

    @Test
    fun `overlayStripLevelsOnXthPage stamps only bottom footer rows without disturbing content above`() {
        val w = 16
        val h = 32
        val pageLevels = ByteArray(w * h) { idx ->
            val y = idx / w
            val x = idx % w
            if (y < 20 && (x + y) % 2 == 0) XtcBuilder.LEVEL_BLACK else XtcBuilder.LEVEL_WHITE
        }
        val xthBlob = XtcBuilder.encodeXthPage(w, h, pageLevels)

        // Stamp a 12-row footer strip at y = 20..31 with LIGHT_GRAY background and a DARK_GRAY center marker
        val stripH = 12
        val stripLevels = ByteArray(w * stripH) { XtcBuilder.LEVEL_LIGHT_GRAY }
        for (y in 4 until 8) {
            for (x in 5 until 11) {
                stripLevels[y * w + x] = XtcBuilder.LEVEL_DARK_GRAY
            }
        }
        XtcBuilder.overlayStripLevelsOnXthPage(
            xthBlob = xthBlob,
            width = w,
            height = h,
            stripTopY = 20,
            stripHeight = stripH,
            stripLevels = stripLevels
        )

        val outFile = tempFolder.newFile("footer_overlay.xtch")
        assertTrue(
            XtcBuilder.buildXtch(
                outputFile = outFile,
                title = "Footer Overlay Test",
                author = "CrossSend",
                pages = listOf(xthBlob),
                width = w,
                height = h
            )
        )

        val decoded = XtcBuilder.decodePagePixels(outFile, 0)
        assertNotNull(decoded)
        val pixels = decoded!!.argbPixels
        // Upper rows (y = 0..19) remain bit-for-bit intact
        for (y in 0 until 20) {
            for (x in 0 until w) {
                val expectedColor = if ((x + y) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                assertEquals("Upper content changed at ($x, $y)", expectedColor, pixels[y * w + x])
            }
        }
        // Footer strip rows (y = 20..31) reflect the overlay levels
        for (y in 20 until h) {
            val sy = y - 20
            for (x in 0 until w) {
                val expectedColor = if (sy in 4 until 8 && x in 5 until 11) {
                    0xFF555555.toInt()
                } else {
                    0xFFAAAAAA.toInt()
                }
                assertEquals("Footer pixel mismatch at ($x, $y)", expectedColor, pixels[y * w + x])
            }
        }
    }
}
