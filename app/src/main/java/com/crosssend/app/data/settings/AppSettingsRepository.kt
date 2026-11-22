package com.crosssend.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.crosssend.app.converter.EpubStylingOptions
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.network.WebDavClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConversionPreset {
    READING_FOCUS,
    MAGAZINE,
    ORIGINAL_LAYOUT,
    CUSTOM
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ShareFlowMode {
    SMART_SHEET,
    AUTO_SEND_COUNTDOWN,
    INSTANT_BACKGROUND
}

data class AppSettings(
    val preset: ConversionPreset = ConversionPreset.READING_FOCUS,
    val defaultRenderMode: RenderMode = RenderMode.READER,
    val epubFontScale: Float = 1.0f,
    val epubLineHeight: Float = 1.45f,
    val epubMarginEm: Float = 0.0f,
    val imageMaxDim: Int = 600,
    val imageJpegQuality: Int = 68,
    val stripTrackingParams: Boolean = true,
    val autoDeleteLocalAfterSync: Boolean = false,
    val shareFlowMode: ShareFlowMode = ShareFlowMode.SMART_SHEET,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val articlesFolder: String = DEFAULT_ARTICLES_FOLDER,
    val booksFolder: String = DEFAULT_BOOKS_FOLDER,
    val textsFolder: String = DEFAULT_TEXTS_FOLDER,
    val sleepFolder: String = DEFAULT_SLEEP_FOLDER
) {
    fun sanitized(): AppSettings {
        val safeFont = if (epubFontScale.isFinite()) {
            epubFontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        } else {
            1.0f
        }
        val safeLineHeight = if (epubLineHeight.isFinite()) {
            epubLineHeight.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
        } else {
            1.45f
        }
        val safeMargin = if (epubMarginEm.isFinite()) {
            epubMarginEm.coerceIn(MIN_MARGIN_EM, MAX_MARGIN_EM)
        } else {
            0.0f
        }
        return copy(
            epubFontScale = safeFont,
            epubLineHeight = safeLineHeight,
            epubMarginEm = safeMargin,
            imageMaxDim = imageMaxDim.coerceIn(MIN_IMAGE_DIM, MAX_IMAGE_DIM),
            imageJpegQuality = imageJpegQuality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY),
            articlesFolder = sanitizeFolderPath(articlesFolder, DEFAULT_ARTICLES_FOLDER),
            booksFolder = sanitizeFolderPath(booksFolder, DEFAULT_BOOKS_FOLDER),
            textsFolder = sanitizeFolderPath(textsFolder, DEFAULT_TEXTS_FOLDER),
            sleepFolder = sanitizeFolderPath(sleepFolder, DEFAULT_SLEEP_FOLDER)
        )
    }

    fun toEpubStylingOptions(): EpubStylingOptions {
        val s = sanitized()
        return EpubStylingOptions(
            fontScale = s.epubFontScale,
            lineHeight = s.epubLineHeight,
            marginEm = s.epubMarginEm
        )
    }

    fun defaultFolderFor(type: DocumentType): String {
        val s = sanitized()
        return when (type) {
            DocumentType.ARTICLE -> s.articlesFolder
            DocumentType.BOOK -> s.booksFolder
            DocumentType.TEXT -> s.textsFolder
            DocumentType.SLEEP -> s.sleepFolder
        }
    }

    fun allTargetDirectories(): List<String> {
        val s = sanitized()
        val ordered = LinkedHashSet<String>()
        val targets = listOf(
            s.articlesFolder,
            s.booksFolder,
            s.textsFolder,
            s.sleepFolder
        )
        for (folder in targets) {
            val clean = WebDavClient.sanitizeRemotePath(folder)
            if (clean == "/") continue
            val segments = clean.trim('/').split('/').filter { it.isNotBlank() }
            var prefix = ""
            for (segment in segments) {
                prefix += "/$segment"
                ordered.add(prefix)
            }
        }
        return ordered.toList()
    }

    companion object {
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.35f
        const val MIN_LINE_HEIGHT = 1.25f
        const val MAX_LINE_HEIGHT = 1.80f
        const val MIN_MARGIN_EM = 0.0f
        const val MAX_MARGIN_EM = 1.5f
        const val MIN_IMAGE_DIM = 320
        const val MAX_IMAGE_DIM = 900
        const val MIN_JPEG_QUALITY = 45
        const val MAX_JPEG_QUALITY = 85

        const val DEFAULT_ARTICLES_FOLDER = "/crosssend/articles"
        const val DEFAULT_BOOKS_FOLDER = "/crosssend/books"
        const val DEFAULT_TEXTS_FOLDER = "/crosssend/texts"
        const val DEFAULT_SLEEP_FOLDER = "/sleep"

        fun sanitizeFolderPath(raw: String, fallback: String): String {
            val safeFallback = WebDavClient.sanitizeRemotePath(fallback)
                .takeIf { it.isNotBlank() && it != "/" }
                ?: DEFAULT_ARTICLES_FOLDER
            val cleaned = WebDavClient.sanitizeRemotePath(raw)
            return if (cleaned.isBlank() || cleaned == "/") {
                safeFallback
            } else {
                cleaned
            }
        }

        fun forPreset(preset: ConversionPreset, base: AppSettings = AppSettings()): AppSettings {
            return when (preset) {
                ConversionPreset.READING_FOCUS -> base.copy(
                    preset = ConversionPreset.READING_FOCUS,
                    defaultRenderMode = RenderMode.READER,
                    epubFontScale = 1.05f,
                    epubLineHeight = 1.50f,
                    epubMarginEm = 0.20f,
                    imageMaxDim = 480,
                    imageJpegQuality = 60
                )
                ConversionPreset.MAGAZINE -> base.copy(
                    preset = ConversionPreset.MAGAZINE,
                    defaultRenderMode = RenderMode.READER,
                    epubFontScale = 1.00f,
                    epubLineHeight = 1.45f,
                    epubMarginEm = 0.00f,
                    imageMaxDim = 760,
                    imageJpegQuality = 78
                )
                ConversionPreset.ORIGINAL_LAYOUT -> base.copy(
                    preset = ConversionPreset.ORIGINAL_LAYOUT,
                    defaultRenderMode = RenderMode.ORIGINAL_LAYOUT,
                    imageMaxDim = 760,
                    imageJpegQuality = 75
                )
                ConversionPreset.CUSTOM -> base.copy(
                    preset = ConversionPreset.CUSTOM
                )
            }.sanitized()
        }
    }
}

class AppSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val writeLock = Any()

    private val _settings = MutableStateFlow(loadFromPrefs())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        return synchronized(writeLock) {
            val updated = transform(_settings.value).sanitized()
            persist(updated)
            _settings.value = updated
            updated
        }
    }

    fun applyPreset(preset: ConversionPreset): AppSettings {
        return update { current -> AppSettings.forPreset(preset, current) }
    }

    private fun loadFromPrefs(): AppSettings {
        val preset = parseEnum(prefs.getString(KEY_PRESET, null), ConversionPreset.READING_FOCUS)
        val renderMode = parseEnum(prefs.getString(KEY_DEFAULT_RENDER_MODE, null), RenderMode.READER)
        val shareMode = parseEnum(prefs.getString(KEY_SHARE_FLOW_MODE, null), ShareFlowMode.SMART_SHEET)
        val themeMode = parseEnum(prefs.getString(KEY_THEME_MODE, null), AppThemeMode.SYSTEM)
        return AppSettings(
            preset = preset,
            defaultRenderMode = renderMode,
            epubFontScale = prefs.getFloat(KEY_EPUB_FONT_SCALE, 1.0f),
            epubLineHeight = prefs.getFloat(KEY_EPUB_LINE_HEIGHT, 1.45f),
            epubMarginEm = prefs.getFloat(KEY_EPUB_MARGIN_EM, 0.0f),
            imageMaxDim = prefs.getInt(KEY_IMAGE_MAX_DIM, 600),
            imageJpegQuality = prefs.getInt(KEY_IMAGE_JPEG_QUALITY, 68),
            stripTrackingParams = prefs.getBoolean(KEY_STRIP_TRACKING_PARAMS, true),
            autoDeleteLocalAfterSync = prefs.getBoolean(KEY_AUTO_DELETE_LOCAL_AFTER_SYNC, false),
            shareFlowMode = shareMode,
            themeMode = themeMode,
            articlesFolder = prefs.getString(KEY_ARTICLES_FOLDER, AppSettings.DEFAULT_ARTICLES_FOLDER)
                ?: AppSettings.DEFAULT_ARTICLES_FOLDER,
            booksFolder = prefs.getString(KEY_BOOKS_FOLDER, AppSettings.DEFAULT_BOOKS_FOLDER)
                ?: AppSettings.DEFAULT_BOOKS_FOLDER,
            textsFolder = prefs.getString(KEY_TEXTS_FOLDER, AppSettings.DEFAULT_TEXTS_FOLDER)
                ?: AppSettings.DEFAULT_TEXTS_FOLDER,
            sleepFolder = prefs.getString(KEY_SLEEP_FOLDER, AppSettings.DEFAULT_SLEEP_FOLDER)
                ?: AppSettings.DEFAULT_SLEEP_FOLDER
        ).sanitized()
    }

    private fun persist(value: AppSettings) {
        prefs.edit {
            putString(KEY_PRESET, value.preset.name)
            putString(KEY_DEFAULT_RENDER_MODE, value.defaultRenderMode.name)
            putFloat(KEY_EPUB_FONT_SCALE, value.epubFontScale)
            putFloat(KEY_EPUB_LINE_HEIGHT, value.epubLineHeight)
            putFloat(KEY_EPUB_MARGIN_EM, value.epubMarginEm)
            putInt(KEY_IMAGE_MAX_DIM, value.imageMaxDim)
            putInt(KEY_IMAGE_JPEG_QUALITY, value.imageJpegQuality)
            putBoolean(KEY_STRIP_TRACKING_PARAMS, value.stripTrackingParams)
            putBoolean(KEY_AUTO_DELETE_LOCAL_AFTER_SYNC, value.autoDeleteLocalAfterSync)
            putString(KEY_SHARE_FLOW_MODE, value.shareFlowMode.name)
            putString(KEY_THEME_MODE, value.themeMode.name)
            putString(KEY_ARTICLES_FOLDER, value.articlesFolder)
            putString(KEY_BOOKS_FOLDER, value.booksFolder)
            putString(KEY_TEXTS_FOLDER, value.textsFolder)
            putString(KEY_SLEEP_FOLDER, value.sleepFolder)
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return try {
            enumValueOf<T>(raw)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    companion object {
        private const val PREFS_NAME = "crosssend_prefs"
        private const val KEY_PRESET = "settings_preset"
        private const val KEY_DEFAULT_RENDER_MODE = "settings_default_render_mode"
        private const val KEY_EPUB_FONT_SCALE = "settings_epub_font_scale"
        private const val KEY_EPUB_LINE_HEIGHT = "settings_epub_line_height"
        private const val KEY_EPUB_MARGIN_EM = "settings_epub_margin_em"
        private const val KEY_IMAGE_MAX_DIM = "settings_image_max_dim"
        private const val KEY_IMAGE_JPEG_QUALITY = "settings_image_jpeg_quality"
        private const val KEY_STRIP_TRACKING_PARAMS = "settings_strip_tracking_params"
        private const val KEY_AUTO_DELETE_LOCAL_AFTER_SYNC = "settings_auto_delete_local_after_sync"
        private const val KEY_SHARE_FLOW_MODE = "settings_share_flow_mode"
        private const val KEY_THEME_MODE = "settings_theme_mode"
        private const val KEY_ARTICLES_FOLDER = "settings_articles_folder"
        private const val KEY_BOOKS_FOLDER = "settings_books_folder"
        private const val KEY_TEXTS_FOLDER = "settings_texts_folder"
        private const val KEY_SLEEP_FOLDER = "settings_sleep_folder"
    }
}
