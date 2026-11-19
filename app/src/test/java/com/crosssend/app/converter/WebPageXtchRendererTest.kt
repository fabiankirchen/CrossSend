package com.crosssend.app.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageXtchRendererTest {

    @Test
    fun `buildPreparePageJs supports non-compact dark containers with light text in normal mode`() {
        val js = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true
        )

        // Helper inspects original light text directly and on transparent-background descendants
        assertTrue(js.contains("function hasContainerOrigLightText(el, isFullPageWrapper)"))
        assertTrue(js.contains("el.dataset.xtchOrigLightText === '1'"))
        assertTrue(js.contains("if (tbg && tbg[3] >= 0.15) continue;"))
        assertTrue(js.contains("var lightenDarkEnabled = true;"))

        // Normal mode promotes any non-full-page dark container (effBgLuma < 110) with light text to #000000 + __xtch_dark_bg
        assertTrue(js.contains("var hasLightText = origLightText || el.classList.contains('__xtch_dark_bg');"))
        assertTrue(js.contains("((!isFullPageWrapper || !lightenDarkEnabled) && effBgLuma < 110 && hasLightText)"))
        assertTrue(js.contains("(isCompactBadgeOrButton && ((effBgLuma < 85) || (effBgLuma < 135 && hasLightText)))"))
        assertTrue(js.contains("el.style.setProperty('background-color', '#000000', 'important');"))
        assertTrue(js.contains("el.classList.add('__xtch_dark_bg');"))

        // Dark-text containers still route through #aaaaaa / #ffffff with __xtch_light_bg when nested inside __xtch_dark_bg
        assertTrue(js.contains("var safeBg = isFullPageWrapper ? '#ffffff' : '#aaaaaa';"))
        assertTrue(js.contains("if (inDarkAncestor) el.classList.add('__xtch_light_bg');"))

        // CSS supports nested dark -> light -> dark contrast recovery
        assertTrue(js.contains(".__xtch_light_bg .__xtch_dark_bg"))
    }

    @Test
    fun `buildPreparePageJs handles lightenDark disabled and inverted dark-page containers`() {
        val jsNoLighten = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = false
        )
        assertTrue(jsNoLighten.contains("var lightenDarkEnabled = false;"))
        assertTrue(jsNoLighten.contains("hasContainerOrigLightText(document.body, true)"))
        assertTrue(jsNoLighten.contains("document.body.style.setProperty('background-color', '#000000', 'important');"))
        assertFalse(jsNoLighten.contains("document.documentElement.dataset.xtchInverted = '1';"))

        val jsInverted = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true
        )
        // Inverted mode maps originally bright containers with dark text (renderedLuma < 110 && !origLightText)
        // to pre-invert #ffffff (renders black) + __xtch_dark_bg (renders white text)
        assertTrue(jsInverted.contains("(!isFullPageWrapper && renderedLuma < 110 && !origLightText)"))
        assertTrue(jsInverted.contains("(isCompactBadgeOrButton && renderedLuma < 85 && !origLightText)"))
        assertTrue(jsInverted.contains("el.style.setProperty('background-color', '#ffffff', 'important');"))
        // Medium/light-text dark containers continue to map to #555555 (renders #aaaaaa) with black text
        assertTrue(jsInverted.contains("el.style.setProperty('background-color', '#555555', 'important');"))
    }

    @Test
    fun `computeSmartPageCuts keeps atomic keepBlocks intact across page boundaries`() {
        val boxes = listOf(
            LayoutBox(top = 0, bottom = 480, isKeepBlock = false),
            LayoutBox(top = 500, bottom = 660, isKeepBlock = true),
            LayoutBox(top = 680, bottom = 1150, isKeepBlock = false)
        )

        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 1200,
            pageHeight = 600,
            firstContentTop = 0,
            rawBoxes = boxes
        )

        assertEquals(3, cuts.size)
        // Page 1 cuts before the straddling keepBlock (500..660)
        assertEquals(0, cuts[0].first)
        assertTrue(cuts[0].second <= 500)
        // Page 2 starts cleanly at the cut and keeps the entire keepBlock (500..660) together
        assertEquals(cuts[0].second, cuts[1].first)
        assertTrue(cuts[1].second >= 660)
        assertEquals(cuts[1].second, cuts[2].first)
        assertEquals(1170, cuts[2].second)
    }

    @Test
    fun `computeSmartPageCuts trims leading blank space and handles degenerate dimensions`() {
        val trimmedCuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 1000,
            pageHeight = 600,
            firstContentTop = 120,
            rawBoxes = listOf(LayoutBox(top = 120, bottom = 900))
        )
        assertEquals(118, trimmedCuts.first().first)

        val degenerateCuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 0,
            pageHeight = 0,
            firstContentTop = 0,
            rawBoxes = emptyList()
        )
        assertEquals(listOf(0 to 100), degenerateCuts)
    }

    @Test
    fun `computeSmartPageCuts merges same-line spans and avoids cutting overlapping lines`() {
        val boxes = listOf(
            // Same visual line split across inline text + <sup> + <a>
            LayoutBox(top = 580, bottom = 602, isKeepBlock = false),
            LayoutBox(top = 577, bottom = 592, isKeepBlock = false),
            LayoutBox(top = 581, bottom = 603, isKeepBlock = false),
            // Next line slightly overlapping previous line's glyph padding
            LayoutBox(top = 600, bottom = 622, isKeepBlock = false),
            LayoutBox(top = 626, bottom = 1000, isKeepBlock = false)
        )

        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 1050,
            pageHeight = 605,
            firstContentTop = 0,
            rawBoxes = boxes
        )

        assertTrue(cuts.size >= 2)
        // First page must cut cleanly before the straddling merged line (577..603), never inside 580..603
        assertTrue(cuts[0].second <= 577)
        assertEquals(cuts[0].second, cuts[1].first)
    }

    @Test
    fun `computeSmartPageCuts keeps tall images intact even when starting near top of page`() {
        val boxes = listOf(
            LayoutBox(top = 0, bottom = 36, isKeepBlock = false),
            LayoutBox(top = 42, bottom = 610, isKeepBlock = true),
            LayoutBox(top = 620, bottom = 950, isKeepBlock = false)
        )

        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 1000,
            pageHeight = 600,
            firstContentTop = 0,
            rawBoxes = boxes
        )

        assertTrue(cuts.size >= 2)
        // Page 1 cuts before the tall straddling image (42..610) instead of slicing it at 594
        assertTrue(cuts[0].second <= 42)
        assertEquals(cuts[0].second, cuts[1].first)
        // Page 2 fits the entire tall image (42..610) without slicing it
        assertTrue(cuts[1].second >= 610)
    }

    @Test
    fun `buildPreparePageJs enforces wrapping and overflow clamping without adding body side padding`() {
        val js = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true
        )

        assertTrue(js.contains("overflow-wrap: break-word !important;"))
        assertTrue(js.contains("white-space: pre-wrap !important; overflow-wrap: anywhere !important;"))
        assertFalse(js.contains("document.body.style.setProperty('padding-left', '10px'"))
        assertFalse(js.contains("document.body.style.setProperty('padding-right', '10px'"))
        assertTrue(js.contains("celClamp.style.setProperty('max-width', '100%', 'important')"))
        assertTrue(js.contains("celClamp.style.setProperty('white-space', 'normal', 'important')"))
    }

    @Test
    fun `computeSmartPageCuts clears straddling text line even when keepBlock pulls cut below minKeepAdvance`() {
        val boxes = listOf(
            LayoutBox(top = 0, bottom = 14, isKeepBlock = false),
            LayoutBox(top = 16, bottom = 32, isKeepBlock = false),
            LayoutBox(top = 28, bottom = 600, isKeepBlock = true),
            LayoutBox(top = 620, bottom = 750, isKeepBlock = false)
        )

        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 760,
            pageHeight = 600,
            firstContentTop = 0,
            rawBoxes = boxes
        )

        assertTrue(cuts.size >= 2)
        // KeepBlock pulls cut to ~24, which would slice [16..32] unless text loop clears it cleanly to <= 16
        assertTrue("Expected Page 1 cut <= 16 to avoid slicing [16..32], got ${cuts[0].second}", cuts[0].second <= 16)
        assertEquals(cuts[0].second, cuts[1].first)
    }

    @Test
    fun `computeSmartPageCuts preserves short trailing last line when totalHeight matches contentBottom`() {
        val boxes = mutableListOf<LayoutBox>()
        var y = 0
        while (y < 572) {
            boxes.add(LayoutBox(top = y, bottom = y + 18, isKeepBlock = false))
            y += 22
        }
        // Final 18px line at [594..612] where totalHeight == 612
        boxes.add(LayoutBox(top = 594, bottom = 612, isKeepBlock = false))

        val cuts = WebPageXtchRenderer.computeSmartPageCuts(
            totalHeight = 612,
            pageHeight = 600,
            firstContentTop = 0,
            rawBoxes = boxes
        )

        assertEquals(2, cuts.size)
        assertTrue("Expected Page 1 cut <= 594 before trailing line, got ${cuts[0].second}", cuts[0].second <= 594)
        assertEquals(612, cuts[1].second)
    }

    @Test
    fun `findCleanCutRowInStrip keeps plannedRelY unchanged on clean whitespace`() {
        val w = 48
        val h = 30
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        // Draw text-like strokes only in rows 2..10 and 20..28, leaving rows 11..19 clean
        for (r in 2..10) {
            for (x in 8 until 40 step 3) {
                pixels[r * w + x] = 0xFF000000.toInt()
            }
        }
        for (r in 20..28) {
            for (x in 8 until 40 step 3) {
                pixels[r * w + x] = 0xFF000000.toInt()
            }
        }

        val cut = WebPageXtchRenderer.findCleanCutRowInStrip(
            stripPixels = pixels,
            width = w,
            stripHeight = h,
            plannedRelY = 15,
            maxSearchUp = 10,
            maxSearchDown = 6
        )

        assertEquals(15, cut)
    }

    @Test
    fun `findCleanCutRowInStrip shifts slightly down to keep descender intact`() {
        val w = 60
        val h = 40
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (r in 8..22) {
            for (x in 10..44 step 3) {
                pixels[r * w + x] = 0xFF000000.toInt()
            }
        }
        // 2px descender on rows 23..24
        for (r in 23..24) {
            pixels[r * w + 16] = 0xFF000000.toInt()
            pixels[r * w + 17] = 0xFF000000.toInt()
        }
        // Next line starts at row 34
        for (r in 34..39) {
            for (x in 10..44 step 3) {
                pixels[r * w + x] = 0xFF000000.toInt()
            }
        }

        val cut = WebPageXtchRenderer.findCleanCutRowInStrip(
            stripPixels = pixels,
            width = w,
            stripHeight = h,
            plannedRelY = 23,
            maxSearchUp = 18,
            maxSearchDown = 6
        )

        assertTrue("Expected downward shift past descender (>= 25 && <= 29), got $cut", cut in 25..29)
    }

    @Test
    fun `findCleanCutRowInStrip shifts upward out of sliced line with static blockquote border and on dark background`() {
        val w = 64
        val h = 42

        // 1) White paper with static 3px vertical blockquote border at x=4..6 and sliced text at rows 25..36
        val quotePixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (r in 0 until h) {
            for (x in 4..6) {
                quotePixels[r * w + x] = 0xFF000000.toInt()
            }
        }
        for (r in 4..14) {
            for (x in 14..52 step 3) {
                quotePixels[r * w + x] = 0xFF000000.toInt()
            }
        }
        for (r in 25..36) {
            for (x in 14..52 step 3) {
                quotePixels[r * w + x] = 0xFF000000.toInt()
            }
        }

        val quoteCut = WebPageXtchRenderer.findCleanCutRowInStrip(
            stripPixels = quotePixels,
            width = w,
            stripHeight = h,
            plannedRelY = 30,
            maxSearchUp = 20,
            maxSearchDown = 4
        )
        assertTrue("Expected cut in clean gap 16..24 despite static border, got $quoteCut", quoteCut in 16..24)

        // 2) Dark #000000 background with white text strokes at rows 4..14 and 25..36
        val darkPixels = IntArray(w * h) { 0xFF000000.toInt() }
        for (r in 4..14) {
            for (x in 10..50 step 3) {
                darkPixels[r * w + x] = 0xFFFFFFFF.toInt()
            }
        }
        for (r in 25..36) {
            for (x in 10..50 step 3) {
                darkPixels[r * w + x] = 0xFFFFFFFF.toInt()
            }
        }

        val darkCut = WebPageXtchRenderer.findCleanCutRowInStrip(
            stripPixels = darkPixels,
            width = w,
            stripHeight = h,
            plannedRelY = 30,
            maxSearchUp = 20,
            maxSearchDown = 4
        )
        assertTrue("Expected cut in dark clean gap 16..24, got $darkCut", darkCut in 16..24)
    }

    @Test
    fun `buildPreparePageJs disables smooth scroll, floors width, preserves body margins, pins headers, and clamps overflow`() {
        val js = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true
        )

        assertTrue(js.contains("scroll-behavior: auto !important; scroll-snap-type: none !important;"))
        assertTrue(js.contains("var actualViewportW = Math.floor(rawViewportW);"))
        assertTrue(js.contains("document.documentElement.dataset.xtchBodyMarginChecked"))
        assertTrue(js.contains("document.body.style.setProperty('padding-left', origML + 'px', 'important')"))
        assertTrue(js.contains("if ((tag === 'header' || tag === 'nav') && (cs.position === 'static' || cs.position === 'relative')"))
        assertTrue(js.contains("if (ccsClamp.whiteSpace === 'nowrap')"))
        assertTrue(js.contains("celClamp.style.setProperty('table-layout', 'fixed', 'important')"))
        assertTrue(js.contains("pClamp.style.setProperty('flex-wrap', 'wrap', 'important')"))
        assertTrue(js.contains("celClamp.style.setProperty('overflow-y', 'visible', 'important')"))
    }

    @Test
    fun `buildPrepareAndMeasureJs reports DPR, viewport dims, uses getTextClipBounds with padding and shadowRoot`() {
        val js = WebPageXtchRenderer.buildPrepareAndMeasureJs(
            fallbackCssW = 360,
            fallbackCssH = 600
        )

        assertTrue(js.contains("dpr: dpr,"))
        assertTrue(js.contains("vvScale: vvScale,"))
        assertTrue(js.contains("viewportCssW: rawViewportW || pageW,"))
        assertTrue(js.contains("viewportCssH: rawViewportH || pageH,"))
        assertTrue(js.contains("function getTextClipBounds(el, elCs)"))
        assertTrue(js.contains("ccs.display !== 'contents'"))
        assertTrue(js.contains("var ctTop = Math.max(rect.top - padTop, clipBounds.top);"))
        assertTrue(js.contains("var ctBot = Math.min(rect.bottom + padBot, clipBounds.bottom);"))
        assertTrue(js.contains("collectTextFromRoot(shadowHosts[si].shadowRoot, depth + 1);"))
    }

    @Test
    fun `buildPreparePageJs prevents picture img double-inversion in dark mode and re-inverts canvas`() {
        val js = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true
        )

        assertTrue(js.contains("if (document.documentElement.dataset.xtchInverted === '1')"))
        assertTrue(js.contains("document.querySelectorAll('img, video, canvas')"))
        assertFalse(js.contains("querySelectorAll('img, picture, video')"))
        assertTrue(js.contains("melInv.parentElement.closest('[data-xtch-media-reinv=\"1\"]')"))
        assertTrue(js.contains("melInv.dataset.xtchMediaReinv = '1';"))
    }

    @Test
    fun `buildPreparePageJs enables auto hyphenation and sanitizes fallbackLang`() {
        val js = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true,
            fallbackLang = "de_AT;alert(1)"
        )

        assertTrue(js.contains("hyphens: auto !important; -webkit-hyphens: auto !important;"))
        assertTrue(js.contains("code, pre, kbd, samp, var { hyphens: none !important; -webkit-hyphens: none !important; }"))
        assertTrue(js.contains("'de-ATalert'"))
        assertFalse(js.contains(";alert(1)"))
    }

    @Test
    fun `computeZoomViewportConfig scales CSS width proportionally and caps safely at 320px`() {
        val zoom75 = WebPageXtchRenderer.computeZoomViewportConfig(75)
        assertEquals(480f, zoom75.cssWidth, 0.01f)
        assertEquals(100, zoom75.textZoomPercent)

        val zoom90 = WebPageXtchRenderer.computeZoomViewportConfig(90)
        assertEquals(400f, zoom90.cssWidth, 0.01f)
        assertEquals(100, zoom90.textZoomPercent)

        val zoom100 = WebPageXtchRenderer.computeZoomViewportConfig(100)
        assertEquals(360f, zoom100.cssWidth, 0.01f)
        assertEquals(100, zoom100.textZoomPercent)

        val zoom110 = WebPageXtchRenderer.computeZoomViewportConfig(110)
        assertTrue(zoom110.cssWidth in 327.0f..327.5f)
        assertEquals(100, zoom110.textZoomPercent)

        val zoom120 = WebPageXtchRenderer.computeZoomViewportConfig(120)
        assertEquals(320f, zoom120.cssWidth, 0.01f)
        assertEquals(107, zoom120.textZoomPercent)

        val zoom140 = WebPageXtchRenderer.computeZoomViewportConfig(140)
        assertEquals(320f, zoom140.cssWidth, 0.01f)
        assertEquals(124, zoom140.textZoomPercent)
    }

    @Test
    fun `downsampleAreaArgb preserves 1px vertical hairline and exact uniform colors`() {
        // 1) Identity copy
        val idSrc = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        val idOut = WebPageXtchRenderer.downsampleAreaArgb(idSrc, 2, 2, 2, 2)
        assertEquals(idSrc.toList(), idOut.toList())

        // 2) Uniform gray remains exact across 3x downsampling
        val graySrc = IntArray(30 * 12) { 0xFFAAAAAA.toInt() }
        val grayOut = WebPageXtchRenderer.downsampleAreaArgb(graySrc, 30, 12, 10, 4)
        assertTrue(grayOut.all { it == 0xFFAAAAAA.toInt() })

        // 3) 1px black hairline at x=12 on white background survives 30x12 -> 10x4 downsampling in column dx=4
        val lineSrc = IntArray(30 * 12) { 0xFFFFFFFF.toInt() }
        for (y in 0 until 12) {
            lineSrc[y * 30 + 12] = 0xFF000000.toInt()
        }
        val lineOut = WebPageXtchRenderer.downsampleAreaArgb(lineSrc, 30, 12, 10, 4)
        for (dy in 0 until 4) {
            assertEquals(0xFFAAAAAA.toInt(), lineOut[dy * 10 + 4])
            assertEquals(0xFFFFFFFF.toInt(), lineOut[dy * 10 + 3])
            assertEquals(0xFFFFFFFF.toInt(), lineOut[dy * 10 + 5])
        }
    }

    @Test
    fun `buildPrepareAndMeasureJs emits widow orphan protection and computeSmartPageCuts prevents single-line orphans and widows`() {
        val js = WebPageXtchRenderer.buildPrepareAndMeasureJs(360, 600)
        assertTrue(js.contains("var maxPairKeepH = Math.floor(pageH * 0.18);"))
        assertTrue(js.contains("var paraEls = document.querySelectorAll('p, li, dd');"))

        // 4-line paragraph straddling bottom (last line alone -> splits 2 + 2)
        val widowBoxes = listOf(
            LayoutBox(0, 500, isKeepBlock = false),
            LayoutBox(510, 532, isKeepBlock = false),
            LayoutBox(536, 558, isKeepBlock = false),
            LayoutBox(562, 584, isKeepBlock = false),
            LayoutBox(588, 610, isKeepBlock = false),
            LayoutBox(510, 558, isKeepBlock = true),
            LayoutBox(562, 610, isKeepBlock = true),
            LayoutBox(620, 800, isKeepBlock = false)
        )
        val widowCuts = WebPageXtchRenderer.computeSmartPageCuts(820, 600, 0, widowBoxes)
        assertEquals(2, widowCuts.size)
        assertTrue("Expected cut between lines 2 and 3 (558..562), got ${widowCuts[0].second}", widowCuts[0].second in 558..562)

        // 3-line paragraph where only line 1 fits before idealEnd=594 (orphan pair moves all 3 lines to page 2)
        val orphanBoxes = listOf(
            LayoutBox(0, 540, isKeepBlock = false),
            LayoutBox(548, 570, isKeepBlock = false),
            LayoutBox(574, 596, isKeepBlock = false),
            LayoutBox(600, 622, isKeepBlock = false),
            LayoutBox(548, 596, isKeepBlock = true),
            LayoutBox(574, 622, isKeepBlock = true),
            LayoutBox(630, 800, isKeepBlock = false)
        )
        val orphanCuts = WebPageXtchRenderer.computeSmartPageCuts(820, 600, 0, orphanBoxes)
        assertEquals(2, orphanCuts.size)
        assertTrue("Expected cut before 548 to prevent single-line orphan, got ${orphanCuts[0].second}", orphanCuts[0].second <= 548)
    }

    @Test
    fun `WebColorScheme fromPrefValue defaults to LIGHT and resolves light or dark case-insensitively`() {
        assertEquals(WebPageXtchRenderer.WebColorScheme.LIGHT, WebPageXtchRenderer.WebColorScheme.fromPrefValue(null))
        assertEquals(WebPageXtchRenderer.WebColorScheme.LIGHT, WebPageXtchRenderer.WebColorScheme.fromPrefValue(""))
        assertEquals(WebPageXtchRenderer.WebColorScheme.LIGHT, WebPageXtchRenderer.WebColorScheme.fromPrefValue("auto"))
        assertEquals(WebPageXtchRenderer.WebColorScheme.LIGHT, WebPageXtchRenderer.WebColorScheme.fromPrefValue("LIGHT"))
        assertEquals(WebPageXtchRenderer.WebColorScheme.DARK, WebPageXtchRenderer.WebColorScheme.fromPrefValue("dark"))
        assertEquals(WebPageXtchRenderer.WebColorScheme.DARK, WebPageXtchRenderer.WebColorScheme.fromPrefValue("DARK"))
    }

    @Test
    fun `buildPreparePageJs omits light-only color-scheme override when darkMode is true`() {
        val jsLight = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = true,
            darkMode = false
        )
        assertTrue(jsLight.contains("color-scheme: light only !important;"))
        assertTrue(jsLight.contains("metaCs.setAttribute('content', 'light only');"))

        val jsDark = WebPageXtchRenderer.buildPreparePageJs(
            fallbackCssW = 360,
            fallbackCssH = 600,
            lightenDark = false,
            darkMode = true
        )
        assertFalse(jsDark.contains("color-scheme: light only !important;"))
        assertFalse(jsDark.contains("metaCs.setAttribute('content', 'light only');"))
        assertFalse(jsDark.contains("document.documentElement.dataset.xtchInverted = '1';"))
        assertTrue(jsDark.contains("var effPageBgNoInv = (bodyCNoInv && bodyCNoInv[3] >= 0.5) ? bodyCNoInv : rootCNoInv;"))
    }
}
