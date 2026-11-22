package com.crosssend.app.data.settings

import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `forPreset applies expected defaults for all four presets`() {
        val focus = AppSettings.forPreset(ConversionPreset.READING_FOCUS)
        assertEquals(ConversionPreset.READING_FOCUS, focus.preset)
        assertEquals(RenderMode.READER, focus.defaultRenderMode)
        assertEquals(1.05f, focus.epubFontScale, 0.0001f)
        assertEquals(1.50f, focus.epubLineHeight, 0.0001f)
        assertEquals(0.20f, focus.epubMarginEm, 0.0001f)
        assertEquals(480, focus.imageMaxDim)
        assertEquals(60, focus.imageJpegQuality)

        val magazine = AppSettings.forPreset(ConversionPreset.MAGAZINE)
        assertEquals(ConversionPreset.MAGAZINE, magazine.preset)
        assertEquals(RenderMode.READER, magazine.defaultRenderMode)
        assertEquals(1.00f, magazine.epubFontScale, 0.0001f)
        assertEquals(1.45f, magazine.epubLineHeight, 0.0001f)
        assertEquals(0.00f, magazine.epubMarginEm, 0.0001f)
        assertEquals(760, magazine.imageMaxDim)
        assertEquals(78, magazine.imageJpegQuality)

        val original = AppSettings.forPreset(ConversionPreset.ORIGINAL_LAYOUT)
        assertEquals(ConversionPreset.ORIGINAL_LAYOUT, original.preset)
        assertEquals(RenderMode.ORIGINAL_LAYOUT, original.defaultRenderMode)
        assertEquals(760, original.imageMaxDim)
        assertEquals(75, original.imageJpegQuality)

        val custom = AppSettings.forPreset(
            ConversionPreset.CUSTOM,
            base = focus.copy(epubFontScale = 1.25f)
        )
        assertEquals(ConversionPreset.CUSTOM, custom.preset)
        assertEquals(1.25f, custom.epubFontScale, 0.0001f)
    }

    @Test
    fun `sanitized clamps numeric bounds and guards against non-finite floats`() {
        val outOfBoundsLow = AppSettings(
            epubFontScale = 0.2f,
            epubLineHeight = 0.5f,
            epubMarginEm = -1.0f,
            imageMaxDim = 100,
            imageJpegQuality = 10
        ).sanitized()
        assertEquals(AppSettings.MIN_FONT_SCALE, outOfBoundsLow.epubFontScale, 0.0001f)
        assertEquals(AppSettings.MIN_LINE_HEIGHT, outOfBoundsLow.epubLineHeight, 0.0001f)
        assertEquals(AppSettings.MIN_MARGIN_EM, outOfBoundsLow.epubMarginEm, 0.0001f)
        assertEquals(AppSettings.MIN_IMAGE_DIM, outOfBoundsLow.imageMaxDim)
        assertEquals(AppSettings.MIN_JPEG_QUALITY, outOfBoundsLow.imageJpegQuality)

        val outOfBoundsHigh = AppSettings(
            epubFontScale = 4.0f,
            epubLineHeight = 5.0f,
            epubMarginEm = 8.0f,
            imageMaxDim = 5000,
            imageJpegQuality = 100
        ).sanitized()
        assertEquals(AppSettings.MAX_FONT_SCALE, outOfBoundsHigh.epubFontScale, 0.0001f)
        assertEquals(AppSettings.MAX_LINE_HEIGHT, outOfBoundsHigh.epubLineHeight, 0.0001f)
        assertEquals(AppSettings.MAX_MARGIN_EM, outOfBoundsHigh.epubMarginEm, 0.0001f)
        assertEquals(AppSettings.MAX_IMAGE_DIM, outOfBoundsHigh.imageMaxDim)
        assertEquals(AppSettings.MAX_JPEG_QUALITY, outOfBoundsHigh.imageJpegQuality)

        val nonFinite = AppSettings(
            epubFontScale = Float.NaN,
            epubLineHeight = Float.POSITIVE_INFINITY,
            epubMarginEm = Float.NEGATIVE_INFINITY
        ).sanitized()
        assertEquals(1.0f, nonFinite.epubFontScale, 0.0001f)
        assertEquals(1.45f, nonFinite.epubLineHeight, 0.0001f)
        assertEquals(0.0f, nonFinite.epubMarginEm, 0.0001f)
    }

    @Test
    fun `toEpubStylingOptions and folder helpers match expected paths and ordering`() {
        val settings = AppSettings(
            epubFontScale = 1.15f,
            epubLineHeight = 1.6f,
            epubMarginEm = 0.5f,
            articlesFolder = "/custom/news",
            booksFolder = "/custom/books",
            textsFolder = "/notes",
            sleepFolder = "/sleep"
        )
        val styling = settings.toEpubStylingOptions()
        assertEquals(1.15f, styling.fontScale, 0.0001f)
        assertEquals(1.6f, styling.lineHeight, 0.0001f)
        assertEquals(0.5f, styling.marginEm, 0.0001f)

        assertEquals("/custom/news", settings.defaultFolderFor(DocumentType.ARTICLE))
        assertEquals("/custom/books", settings.defaultFolderFor(DocumentType.BOOK))
        assertEquals("/notes", settings.defaultFolderFor(DocumentType.TEXT))
        assertEquals("/sleep", settings.defaultFolderFor(DocumentType.SLEEP))

        assertEquals(
            listOf("/custom", "/custom/news", "/custom/books", "/notes", "/sleep"),
            settings.allTargetDirectories()
        )
    }

    @Test
    fun `sanitizeFolderPath strips traversal segments and falls back on blank or root`() {
        val traversed = AppSettings.sanitizeFolderPath("../evil", AppSettings.DEFAULT_ARTICLES_FOLDER)
        assertFalse(traversed.contains(".."))
        assertEquals("/evil", traversed)

        assertEquals(
            AppSettings.DEFAULT_ARTICLES_FOLDER,
            AppSettings.sanitizeFolderPath("../..", AppSettings.DEFAULT_ARTICLES_FOLDER)
        )
        assertEquals(
            AppSettings.DEFAULT_ARTICLES_FOLDER,
            AppSettings.sanitizeFolderPath("/", AppSettings.DEFAULT_ARTICLES_FOLDER)
        )
        assertEquals(
            AppSettings.DEFAULT_ARTICLES_FOLDER,
            AppSettings.sanitizeFolderPath("   ", AppSettings.DEFAULT_ARTICLES_FOLDER)
        )
        assertEquals(
            "/library/sub",
            AppSettings.sanitizeFolderPath("\\library\\..\\sub\\", AppSettings.DEFAULT_BOOKS_FOLDER)
        )
    }
}
