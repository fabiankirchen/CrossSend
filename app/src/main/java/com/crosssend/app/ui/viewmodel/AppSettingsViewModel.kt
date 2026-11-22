package com.crosssend.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.data.local.DomainPreferenceEntity
import com.crosssend.app.data.model.DocumentType
import com.crosssend.app.data.model.RenderMode
import com.crosssend.app.data.settings.AppSettings
import com.crosssend.app.data.settings.AppSettingsRepository
import com.crosssend.app.data.settings.AppThemeMode
import com.crosssend.app.data.settings.ConversionPreset
import com.crosssend.app.data.settings.ShareFlowMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CrossSendApp
        get() = getApplication()

    private val repository: AppSettingsRepository = app.settingsRepository
    private val domainPreferenceDao = app.database.domainPreferenceDao()

    val settings: StateFlow<AppSettings> = repository.settings

    val domainPreferences: StateFlow<List<DomainPreferenceEntity>> = domainPreferenceDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = computeClearableCacheBytes()
        }
    }

    fun clearAppCache(onDone: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val before = computeClearableCacheBytes()
            val cacheDir = app.cacheDir
            for (subdir in CACHE_SUBDIRS) {
                val target = File(cacheDir, subdir)
                if (target.exists()) {
                    try {
                        target.deleteRecursively()
                    } catch (_: Exception) {}
                }
            }
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && STALE_TEMP_PREFIXES.any { prefix -> file.name.startsWith(prefix) }) {
                    try {
                        file.delete()
                    } catch (_: Exception) {}
                }
            }
            val after = computeClearableCacheBytes()
            _cacheSizeBytes.value = after
            val freed = (before - after).coerceAtLeast(0L)
            withContext(Dispatchers.Main) {
                onDone(freed)
            }
        }
    }

    fun applyPreset(preset: ConversionPreset) {
        repository.applyPreset(preset)
    }

    fun setDefaultRenderMode(mode: RenderMode) {
        repository.update { it.copy(defaultRenderMode = mode, preset = ConversionPreset.CUSTOM) }
    }

    fun setEpubFontScale(scale: Float) {
        repository.update { it.copy(epubFontScale = scale, preset = ConversionPreset.CUSTOM) }
    }

    fun setEpubLineHeight(lineHeight: Float) {
        repository.update { it.copy(epubLineHeight = lineHeight, preset = ConversionPreset.CUSTOM) }
    }

    fun setEpubMarginEm(marginEm: Float) {
        repository.update { it.copy(epubMarginEm = marginEm, preset = ConversionPreset.CUSTOM) }
    }

    fun setImageMaxDim(maxDim: Int) {
        repository.update { it.copy(imageMaxDim = maxDim, preset = ConversionPreset.CUSTOM) }
    }

    fun setImageJpegQuality(quality: Int) {
        repository.update { it.copy(imageJpegQuality = quality, preset = ConversionPreset.CUSTOM) }
    }

    fun setShareFlowMode(mode: ShareFlowMode) {
        repository.update { it.copy(shareFlowMode = mode) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        repository.update { it.copy(themeMode = mode) }
    }

    fun setStripTrackingParams(enabled: Boolean) {
        repository.update { it.copy(stripTrackingParams = enabled) }
    }

    fun setAutoDeleteLocalAfterSync(enabled: Boolean) {
        repository.update { it.copy(autoDeleteLocalAfterSync = enabled) }
    }

    fun setFolder(type: DocumentType, rawPath: String) {
        repository.update { current ->
            when (type) {
                DocumentType.ARTICLE -> current.copy(articlesFolder = rawPath)
                DocumentType.BOOK -> current.copy(booksFolder = rawPath)
                DocumentType.TEXT -> current.copy(textsFolder = rawPath)
                DocumentType.SLEEP -> current.copy(sleepFolder = rawPath)
            }
        }
    }

    fun toggleDomainRule(pref: DomainPreferenceEntity) {
        val nextMode = if (pref.renderMode == RenderMode.ORIGINAL_LAYOUT) {
            RenderMode.READER
        } else {
            RenderMode.ORIGINAL_LAYOUT
        }
        viewModelScope.launch(Dispatchers.IO) {
            domainPreferenceDao.upsert(
                pref.copy(renderMode = nextMode, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun deleteDomainRule(host: String) {
        viewModelScope.launch(Dispatchers.IO) {
            domainPreferenceDao.deleteByHost(host)
        }
    }

    private fun computeClearableCacheBytes(): Long {
        val cacheDir = app.cacheDir
        var total = 0L
        for (subdir in CACHE_SUBDIRS) {
            val target = File(cacheDir, subdir)
            if (target.exists()) {
                total += directorySizeBytes(target)
            }
        }
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && STALE_TEMP_PREFIXES.any { prefix -> file.name.startsWith(prefix) }) {
                total += file.length().coerceAtLeast(0L)
            }
        }
        return total
    }

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length().coerceAtLeast(0L)
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) {
                    total += child.length().coerceAtLeast(0L)
                } else if (child.isDirectory) {
                    stack.addLast(child)
                }
            }
        }
        return total
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val CACHE_SUBDIRS = listOf(
            "covers",
            "rss_thumbs",
            "epub_images",
            "incoming_shares",
            "debug_dumps"
        )
        private val STALE_TEMP_PREFIXES = listOf(
            "xtch_build_",
            "xtch_stream_",
            "pdf_cover_",
            "pdf_extract_",
            "sd_upload_",
            "sd_transfer_",
            "font_"
        )
    }
}
