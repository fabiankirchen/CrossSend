package com.crosssend.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.DocumentProcessor
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.network.CrossPointDeviceClient
import com.crosssend.app.network.CrossPointSettingItem
import com.crosssend.app.network.CrossPointStatus
import com.crosssend.app.network.LocalDeviceHostValidator
import com.crosssend.app.network.RemoteDavItem
import com.crosssend.app.network.WebDavClient
import com.crosssend.app.network.WifiConnectionState
import com.crosssend.app.ui.screens.CatalogPlugin
import com.crosssend.app.ui.screens.PluginInstallOutcome
import com.crosssend.app.ui.screens.SdDownloadOutcome
import com.crosssend.app.ui.screens.SdPluginInfo
import com.crosssend.app.ui.screens.deleteItemFromSdCard
import com.crosssend.app.ui.screens.downloadItemFromSdCard
import com.crosssend.app.ui.screens.ensureInsideDirectory
import com.crosssend.app.ui.screens.fetchPluginCatalog
import com.crosssend.app.ui.screens.installCatalogPluginToDevice
import com.crosssend.app.ui.screens.isAllowedPluginConfigPath
import com.crosssend.app.ui.screens.isSafeSdCardPath
import com.crosssend.app.ui.screens.parentSdCardPath
import com.crosssend.app.ui.screens.parseManifestPermissions
import com.crosssend.app.ui.screens.uploadUriToSdCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * ViewModel for the CrossPoint device management screen: connection state, device status,
 * reader hardware settings, SD card file explorer, vector font upload, SD plugins, and plugin store.
 */
class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val app: CrossSendApp
        get() = getApplication()

    val connectionState: StateFlow<WifiConnectionState> = app.wifiManager.connectionState
    val isSyncing: StateFlow<Boolean> = app.syncManager.isSyncing

    val isConnected: StateFlow<Boolean> = connectionState
        .map { state ->
            state is WifiConnectionState.ConnectedToCrossPointAp ||
                state is WifiConnectionState.ConnectedOnLocalNetwork
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), app.wifiManager.isConnected())

    private val _currentBaseUrl = MutableStateFlow(app.webDavClient.getBaseUrl())
    val currentBaseUrl: StateFlow<String> = _currentBaseUrl.asStateFlow()

    private val _apSsid = MutableStateFlow(app.wifiManager.getApSsid())
    val apSsid: StateFlow<String> = _apSsid.asStateFlow()

    private val _lanBaseUrl = MutableStateFlow(app.wifiManager.getLanBaseUrl())
    val lanBaseUrl: StateFlow<String> = _lanBaseUrl.asStateFlow()

    private val _status = MutableStateFlow<CrossPointStatus?>(null)
    val status: StateFlow<CrossPointStatus?> = _status.asStateFlow()

    private val _settingsList = MutableStateFlow<List<CrossPointSettingItem>>(emptyList())
    val settingsList: StateFlow<List<CrossPointSettingItem>> = _settingsList.asStateFlow()

    private val _isLoadingSettings = MutableStateFlow(false)
    val isLoadingSettings: StateFlow<Boolean> = _isLoadingSettings.asStateFlow()

    // SD Plugins & Store
    private val _sdPlugins = MutableStateFlow<List<SdPluginInfo>>(emptyList())
    val sdPlugins: StateFlow<List<SdPluginInfo>> = _sdPlugins.asStateFlow()

    private val _isLoadingPlugins = MutableStateFlow(false)
    val isLoadingPlugins: StateFlow<Boolean> = _isLoadingPlugins.asStateFlow()

    private val _catalogPlugins = MutableStateFlow<List<CatalogPlugin>>(emptyList())
    val catalogPlugins: StateFlow<List<CatalogPlugin>> = _catalogPlugins.asStateFlow()

    private val _isLoadingCatalog = MutableStateFlow(false)
    val isLoadingCatalog: StateFlow<Boolean> = _isLoadingCatalog.asStateFlow()

    private val _installingPluginName = MutableStateFlow<String?>(null)
    val installingPluginName: StateFlow<String?> = _installingPluginName.asStateFlow()

    private val _pluginStoreSearchQuery = MutableStateFlow("")
    val pluginStoreSearchQuery: StateFlow<String> = _pluginStoreSearchQuery.asStateFlow()

    private val _pluginStoreSelectedFilter = MutableStateFlow(0)
    val pluginStoreSelectedFilter: StateFlow<Int> = _pluginStoreSelectedFilter.asStateFlow()

    private val _pluginToConfigure = MutableStateFlow<SdPluginInfo?>(null)
    val pluginToConfigure: StateFlow<SdPluginInfo?> = _pluginToConfigure.asStateFlow()

    private val _deviceWebUiUrl = MutableStateFlow<String?>(null)
    val deviceWebUiUrl: StateFlow<String?> = _deviceWebUiUrl.asStateFlow()

    // SD Card Explorer
    private val _currentDirectory = MutableStateFlow("/")
    val currentDirectory: StateFlow<String> = _currentDirectory.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteDavItem>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteDavItem>> = _remoteFiles.asStateFlow()

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    private val _fileLoadError = MutableStateFlow<String?>(null)
    val fileLoadError: StateFlow<String?> = _fileLoadError.asStateFlow()

    private val _isUploadingFile = MutableStateFlow(false)
    val isUploadingFile: StateFlow<Boolean> = _isUploadingFile.asStateFlow()

    private val _isDownloadingFile = MutableStateFlow(false)
    val isDownloadingFile: StateFlow<Boolean> = _isDownloadingFile.asStateFlow()

    private val _isUploadingFont = MutableStateFlow(false)
    val isUploadingFont: StateFlow<Boolean> = _isUploadingFont.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UiMessage> = _uiEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is WifiConnectionState.ConnectedToCrossPointAp ||
                    state is WifiConnectionState.ConnectedOnLocalNetwork
                ) {
                    _currentBaseUrl.value = app.webDavClient.getBaseUrl()
                }
            }
        }
    }

    fun updateQuickLanAddress(rawUrl: String): Boolean {
        val normalized = LocalDeviceHostValidator.normalizeLocalDeviceUrl(rawUrl)
        if (normalized == null) {
            _uiEvents.tryEmit(UiMessage.Resource(R.string.device_error_invalid_address))
            return false
        }
        app.wifiManager.setLanBaseUrl(normalized)
        app.webDavClient.updateBaseUrl(normalized)
        _currentBaseUrl.value = normalized
        _lanBaseUrl.value = normalized
        _uiEvents.tryEmit(UiMessage.Resource(R.string.device_quick_address_saved, listOf(normalized)))
        return true
    }

    fun updateConnectionConfig(ssid: String, rawUrl: String): Boolean {
        val cleanSsid = ssid.trim().ifBlank { "Cross" }
        app.wifiManager.setApSsid(cleanSsid)
        _apSsid.value = cleanSsid
        return updateQuickLanAddress(rawUrl)
    }

    fun connectOrDiscover() {
        viewModelScope.launch {
            app.wifiManager.connectOrDiscover(app.webDavClient)
            _currentBaseUrl.value = app.webDavClient.getBaseUrl()
        }
    }

    fun connectToHotspot() {
        app.wifiManager.connectToCrossPointAp()
    }

    fun cancelConnectionAttempt() {
        app.wifiManager.cancelConnectionAttempt()
    }

    fun refreshStatus() {
        if (!app.wifiManager.isConnected()) return
        val baseUrl = _currentBaseUrl.value
        viewModelScope.launch {
            val statusRes = app.deviceClient.fetchStatus(baseUrl)
            if (statusRes.isSuccess) {
                val st = statusRes.getOrNull()
                _status.value = st
                persistDetectedModelIfSpecific(st)
            }
        }
    }

    fun loadDeviceData() {
        if (!app.wifiManager.isConnected()) return
        val baseUrl = _currentBaseUrl.value
        viewModelScope.launch {
            _isLoadingSettings.value = true
            try {
                val statusRes = app.deviceClient.fetchStatus(baseUrl)
                if (statusRes.isSuccess) {
                    val st = statusRes.getOrNull()
                    _status.value = st
                    persistDetectedModelIfSpecific(st)
                }
                val settingsRes = app.deviceClient.fetchSettings(baseUrl)
                if (settingsRes.isSuccess) {
                    _settingsList.value = settingsRes.getOrDefault(emptyList())
                }
            } finally {
                _isLoadingSettings.value = false
            }
            loadSdPlugins()
        }
    }

    fun updateSetting(key: String, newValue: Any) {
        _settingsList.value = _settingsList.value.map { item ->
            if (item.key == key) item.copy(value = newValue) else item
        }
        val baseUrl = _currentBaseUrl.value
        viewModelScope.launch {
            val res = app.deviceClient.updateSetting(baseUrl, key, newValue)
            if (res.isSuccess) {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_setting_saved, listOf(key)))
            } else {
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.dev_setting_save_error,
                        listOf(res.exceptionOrNull()?.message.orEmpty())
                    )
                )
                loadDeviceData()
            }
        }
    }

    fun loadDirectory(path: String = _currentDirectory.value) {
        if (!app.wifiManager.isConnected() || !isSafeSdCardPath(path)) return
        viewModelScope.launch {
            _isLoadingFiles.value = true
            _fileLoadError.value = null
            val result = withContext(Dispatchers.IO) {
                app.webDavClient.listDirectoryResult(path)
            }
            if (result.isSuccess) {
                _remoteFiles.value = result.getOrDefault(emptyList())
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                _currentDirectory.value = path
                _fileLoadError.value = null
            } else {
                _fileLoadError.value = result.exceptionOrNull()?.message
                    ?: app.resources.getString(R.string.wifi_error_connection)
            }
            _isLoadingFiles.value = false
        }
    }

    fun navigateUpDirectory() {
        val current = _currentDirectory.value
        if (current != "/" && current.isNotBlank()) {
            loadDirectory(parentSdCardPath(current))
        }
    }

    fun createDirectory(path: String) {
        if (!app.wifiManager.isConnected() || !isSafeSdCardPath(path)) return
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.ensureDirectoryExists(path)
            }
            if (success) {
                loadDirectory(_currentDirectory.value)
            }
        }
    }

    fun uploadFileToCurrentDir(uri: Uri) {
        val targetDir = _currentDirectory.value
        viewModelScope.launch {
            _isUploadingFile.value = true
            try {
                val result = uploadUriToSdCard(app, app, uri, targetDir)
                result.onSuccess { (safeFileName, success) ->
                    if (success) {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_upload_success, listOf(safeFileName)))
                        loadDirectory(targetDir)
                    } else {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_upload_failed, listOf(safeFileName)))
                    }
                }.onFailure { e ->
                    if (e !is SecurityException) {
                        _uiEvents.emit(
                            UiMessage.Resource(
                                R.string.dev_file_upload_error,
                                listOf(e.message.orEmpty())
                            )
                        )
                    }
                }
            } finally {
                _isUploadingFile.value = false
            }
        }
    }

    fun downloadRemoteItem(item: RemoteDavItem) {
        viewModelScope.launch {
            _isDownloadingFile.value = true
            val safeName = File(item.name).name.ifBlank { "download.bin" }
            _uiEvents.emit(UiMessage.Resource(R.string.dev_file_downloading, listOf(safeName)))
            try {
                when (val outcome = downloadItemFromSdCard(app, app, item)) {
                    is SdDownloadOutcome.Saved -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_download_saved, listOf(outcome.fileName)))
                    }
                    SdDownloadOutcome.SaveFailed -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_download_save_failed))
                    }
                    is SdDownloadOutcome.DownloadFailed -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_download_failed, listOf(outcome.fileName)))
                    }
                    is SdDownloadOutcome.Error -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_file_download_error, listOf(outcome.message)))
                    }
                }
            } finally {
                _isDownloadingFile.value = false
            }
        }
    }

    fun deleteRemoteItem(item: RemoteDavItem) {
        viewModelScope.launch {
            val success = deleteItemFromSdCard(app, item)
            if (success) {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_item_deleted, listOf(item.name)))
                loadDirectory(_currentDirectory.value)
            } else {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_delete_failed))
            }
        }
    }

    fun uploadSleepScreen(uri: Uri) {
        _uiEvents.tryEmit(UiMessage.Resource(R.string.main_toast_preparing_sleepscreen))
        app.applicationScope.launch {
            try {
                val doc = app.documentProcessor.processSleepScreenUri(uri)
                if (doc != null) {
                    _uiEvents.emit(UiMessage.Resource(R.string.main_snackbar_sleepscreen_transferred))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.main_snackbar_sleepscreen_error,
                        listOf(e.localizedMessage ?: "")
                    )
                )
            }
        }
    }

    fun extractSafeFontFileName(uri: Uri): String? {
        if (!DocumentProcessor.isSafeSharedUri(app, uri)) return null
        var fontFileName = "font_${System.currentTimeMillis()}.ttf"
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1) {
                    fontFileName = cursor.getString(nameIdx)
                }
            }
        }
        val safeFontName = File(
            fontFileName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
        ).name
        val lower = safeFontName.lowercase(Locale.ROOT)
        val valid = lower.endsWith(".cpfont") ||
            lower.endsWith(".ttf") ||
            lower.endsWith(".otf") ||
            lower.endsWith(".ttc")
        if (!valid) {
            _uiEvents.tryEmit(UiMessage.Resource(R.string.dev_font_invalid_format))
            return null
        }
        return safeFontName
    }

    fun uploadFont(uri: Uri, fileName: String, familyName: String) {
        if (!DocumentProcessor.isSafeSharedUri(app, uri)) return
        val baseUrl = _currentBaseUrl.value
        viewModelScope.launch {
            _isUploadingFont.value = true
            try {
                val safeFontName = File(fileName).name.ifBlank { "font_${System.currentTimeMillis()}.ttf" }
                val fontExt = safeFontName.substringAfterLast('.', "ttf").takeIf { it.isNotBlank() } ?: "ttf"
                val tempFile = ensureInsideDirectory(app.cacheDir, File.createTempFile("font_", ".$fontExt", app.cacheDir))
                val res = try {
                    withContext(Dispatchers.IO) {
                        val inputStream = app.contentResolver.openInputStream(uri)
                            ?: throw IOException("Cannot open selected font")
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var totalRead = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    totalRead += read
                                    if (totalRead > MAX_FONT_UPLOAD_BYTES) {
                                        throw IOException("Font file exceeds 30 MB limit")
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    app.deviceClient.uploadFont(baseUrl, familyName, tempFile)
                } finally {
                    tempFile.delete()
                }
                if (res.isSuccess) {
                    _uiEvents.emit(
                        UiMessage.Resource(
                            R.string.dev_font_upload_success,
                            listOf(familyName.ifBlank { safeFontName })
                        )
                    )
                } else {
                    _uiEvents.emit(
                        UiMessage.Resource(
                            R.string.dev_font_upload_failed,
                            listOf(res.exceptionOrNull()?.message.orEmpty())
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiEvents.emit(
                    UiMessage.Resource(
                        R.string.dev_font_upload_failed,
                        listOf(e.message.orEmpty())
                    )
                )
            } finally {
                _isUploadingFont.value = false
            }
        }
    }

    fun loadSdPlugins() {
        if (!app.wifiManager.isConnected()) return
        val baseUrl = _currentBaseUrl.value
        viewModelScope.launch {
            _isLoadingPlugins.value = true
            val plugins = mutableListOf<SdPluginInfo>()
            try {
                val searchDirs = listOf("/.crosspoint/plugins", "/plugins", "/.plugins")
                val (apiPlugins, dirListings) = coroutineScope {
                    val apiDeferred = async(Dispatchers.IO) {
                        app.deviceClient.fetchPlugins(baseUrl).getOrDefault(emptyList())
                    }
                    val dirDeferreds = searchDirs.map { searchDir ->
                        async(Dispatchers.IO) {
                            searchDir to app.webDavClient.listDirectory(searchDir)
                        }
                    }
                    apiDeferred.await() to dirDeferreds.awaitAll()
                }

                val processedNames = mutableSetOf<String>()
                val uniquePluginTargets = mutableListOf<Pair<String, String>>()
                for ((searchDir, rootItems) in dirListings) {
                    for (item in rootItems) {
                        if (item.isDirectory && processedNames.add(item.name)) {
                            uniquePluginTargets.add(searchDir to item.name)
                        }
                    }
                }

                val loadedPlugins = coroutineScope {
                    uniquePluginTargets.map { (searchDir, folderName) ->
                        async(Dispatchers.IO) {
                            val manifestDeferred = async {
                                app.webDavClient.readTextFile("$searchDir/$folderName/manifest.json")
                            }
                            val deviceDeferred = async {
                                app.webDavClient.readTextFile("$searchDir/$folderName/device.json")
                            }
                            val pluginJsDeferred = async {
                                app.webDavClient.readTextFile("$searchDir/$folderName/plugin.js")
                            }

                            val manifestStr = manifestDeferred.await()
                            val deviceStr = deviceDeferred.await()

                            var configFile: String? = null
                            if (!deviceStr.isNullOrBlank()) {
                                try {
                                    val devJson = JSONObject(deviceStr)
                                    if (devJson.has("config") && devJson.getJSONObject("config").has("file")) {
                                        configFile = devJson.getJSONObject("config").getString("file")
                                    } else if (devJson.has("token") && devJson.getJSONObject("token").has("file")) {
                                        configFile = devJson.getJSONObject("token").getString("file")
                                    }
                                } catch (_: Exception) {}
                            }
                            configFile = configFile
                                ?.let { WebDavClient.sanitizeRemotePath(it) }
                                ?.takeIf { isAllowedPluginConfigPath(folderName, searchDir, it) }
                            if (configFile == null) {
                                configFile = when {
                                    folderName.equals("webdav", true) -> "/.crosspoint/webdav.json"
                                    folderName.equals("wallabag", true) -> "/.crosspoint/wallabag.json"
                                    folderName.equals("bookfusion", true) -> "/.crosspoint/bookfusion.json"
                                    folderName.equals("monthwallpaper", true) ||
                                        folderName.equals("month-wallpaper", true) -> "/.crosspoint/month-wallpaper.json"
                                    else -> null
                                }
                            }

                            val configStr = if (configFile != null) {
                                app.webDavClient.readTextFile(configFile)
                            } else {
                                app.webDavClient.readTextFile("$searchDir/$folderName/config.json")
                            }
                            val pluginJsStr = pluginJsDeferred.await()

                            val apiMatch = apiPlugins.firstOrNull { it.name.equals(folderName, true) }
                            var name = apiMatch?.title ?: folderName.replace('_', ' ').replace('-', ' ')
                                .replaceFirstChar { it.uppercase() }
                            var version = "1.0.0"
                            var description = if (apiMatch != null) {
                                app.resources.getString(R.string.dev_plugin_mount, apiMatch.mount)
                            } else {
                                app.resources.getString(R.string.dev_plugin_default_description)
                            }
                            var author = ""
                            var isEnabled = !folderName.endsWith(".disabled", true)

                            if (!manifestStr.isNullOrBlank()) {
                                try {
                                    val json = JSONObject(manifestStr)
                                    name = json.optString("title", json.optString("name", name))
                                    version = json.optString("version", version)
                                    description = json.optString("description", description)
                                    author = json.optString("author", author)
                                    if (json.has("enabled")) {
                                        isEnabled = json.optBoolean("enabled", isEnabled)
                                    }
                                } catch (_: Exception) {}
                            }

                            val (hasDeclaredPermissions, permissions) = parseManifestPermissions(manifestStr)

                            SdPluginInfo(
                                id = folderName,
                                folderName = folderName,
                                parentDir = searchDir,
                                configFile = configFile,
                                name = name,
                                version = version,
                                description = description,
                                author = author,
                                isEnabled = isEnabled,
                                configJson = configStr,
                                pluginJs = pluginJsStr,
                                deviceJson = deviceStr,
                                manifestJson = manifestStr,
                                permissions = permissions,
                                hasDeclaredPermissions = hasDeclaredPermissions
                            )
                        }
                    }.awaitAll()
                }
                plugins.addAll(loadedPlugins)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore if plugin folder does not exist yet
            }
            _sdPlugins.value = plugins
            _isLoadingPlugins.value = false
        }
    }

    fun initPluginsDirectory() {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.ensureDirectoryExists("/plugins")
            }
            if (success) {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_plugins_folder_ready))
                loadSdPlugins()
            } else {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_plugins_folder_create_failed))
            }
        }
    }

    fun togglePlugin(plugin: SdPluginInfo, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val manifestPath = "${plugin.parentDir}/${plugin.folderName}/manifest.json"
                val existingManifest = app.webDavClient.readTextFile(manifestPath)
                val json = if (!existingManifest.isNullOrBlank()) {
                    try { JSONObject(existingManifest) } catch (_: Exception) { JSONObject() }
                } else {
                    JSONObject().apply {
                        put("id", plugin.id)
                        put("name", plugin.name)
                        put("version", plugin.version)
                        put("description", plugin.description)
                    }
                }
                json.put("enabled", enabled)
                app.webDavClient.writeTextFile(manifestPath, json.toString(2))
            }
            val statusStr = app.resources.getString(
                if (enabled) R.string.dev_plugin_enabled else R.string.dev_plugin_disabled
            )
            _uiEvents.emit(
                UiMessage.Resource(
                    R.string.dev_plugin_status_toggled,
                    listOf(plugin.name, statusStr)
                )
            )
            loadSdPlugins()
        }
    }

    fun openPluginConfig(plugin: SdPluginInfo) {
        viewModelScope.launch {
            var enriched = plugin
            if (enriched.pluginJs.isNullOrBlank() || enriched.configJson == null) {
                val pJs = withContext(Dispatchers.IO) {
                    app.webDavClient.readTextFile("${enriched.parentDir}/${enriched.folderName}/plugin.js")
                }
                val cfgFile = enriched.configFile
                val cfg = withContext(Dispatchers.IO) {
                    if (cfgFile != null) {
                        app.webDavClient.readTextFile(cfgFile)
                    } else {
                        app.webDavClient.readTextFile("${enriched.parentDir}/${enriched.folderName}/config.json")
                    }
                }
                enriched = enriched.copy(
                    pluginJs = if (!pJs.isNullOrBlank()) pJs else enriched.pluginJs,
                    configJson = cfg ?: enriched.configJson
                )
            }
            _pluginToConfigure.value = enriched
        }
    }

    fun closePluginConfig() {
        _pluginToConfigure.value = null
    }

    fun savePluginConfig(plugin: SdPluginInfo, newConfigJson: String) {
        viewModelScope.launch {
            val configPath = plugin.configFile ?: "${plugin.parentDir}/${plugin.folderName}/config.json"
            val ok = withContext(Dispatchers.IO) {
                app.webDavClient.writeTextFile(configPath, newConfigJson)
            }
            if (ok) {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_settings_saved))
                _pluginToConfigure.value = plugin.copy(configJson = newConfigJson)
                loadSdPlugins()
            } else {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_settings_save_failed))
            }
        }
    }

    fun loadPluginCatalog() {
        viewModelScope.launch {
            _isLoadingCatalog.value = true
            try {
                _catalogPlugins.value = fetchPluginCatalog(
                    app = app,
                    protectedContentDescription = app.resources.getString(R.string.dev_plugin_protected_content_desc)
                )
            } finally {
                _isLoadingCatalog.value = false
            }
        }
    }

    fun installCatalogPlugin(plugin: CatalogPlugin) {
        viewModelScope.launch {
            val safePluginName = plugin.name.trim()
            val existingParentDir = _sdPlugins.value.firstOrNull {
                it.folderName.equals(safePluginName, ignoreCase = true)
            }?.parentDir
            _installingPluginName.value = safePluginName
            try {
                when (val outcome = installCatalogPluginToDevice(app, app.resources, plugin, existingParentDir)) {
                    PluginInstallOutcome.Success -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_plugin_installed_success, listOf(plugin.title)))
                        loadSdPlugins()
                    }
                    PluginInstallOutcome.InvalidPluginName -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_invalid_plugin_name))
                    }
                    PluginInstallOutcome.UnsafeUrl -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_plugin_unsafe_url))
                    }
                    PluginInstallOutcome.UploadFailed -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_plugin_install_failed, listOf(plugin.title)))
                    }
                    is PluginInstallOutcome.Error -> {
                        _uiEvents.emit(UiMessage.Resource(R.string.dev_plugin_install_error, listOf(outcome.message)))
                    }
                }
            } finally {
                _installingPluginName.value = null
            }
        }
    }

    fun uninstallPlugin(plugin: SdPluginInfo) {
        val targetPath = "${plugin.parentDir.trimEnd('/')}/${plugin.folderName}"
        if (!isSafeSdCardPath(targetPath)) return
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.deleteFile(targetPath)
            }
            if (success) {
                if (_pluginToConfigure.value?.folderName == plugin.folderName) {
                    _pluginToConfigure.value = null
                }
                _uiEvents.emit(UiMessage.Resource(R.string.dev_item_deleted, listOf(plugin.name)))
                loadSdPlugins()
            } else {
                _uiEvents.emit(UiMessage.Resource(R.string.dev_delete_failed))
            }
        }
    }

    fun setPluginStoreSearchQuery(query: String) {
        _pluginStoreSearchQuery.value = query
    }

    fun setPluginStoreSelectedFilter(filter: Int) {
        _pluginStoreSelectedFilter.value = filter
    }

    fun openDeviceWebUi() {
        _deviceWebUiUrl.value = _currentBaseUrl.value
    }

    fun closeDeviceWebUi() {
        _deviceWebUiUrl.value = null
    }

    private fun persistDetectedModelIfSpecific(status: CrossPointStatus?) {
        val model = status?.deviceModel ?: return
        if (!CrossPointDeviceClient.isSpecificDetectedModel(model)) return
        val profile = EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(model)
        app.getSharedPreferences(CROSSPOINT_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("device_model", profile.id)
        }
    }

    companion object {
        private const val CROSSPOINT_PREFS_NAME = "crosspoint_prefs"
        private const val MAX_FONT_UPLOAD_BYTES = 30L * 1024 * 1024
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
