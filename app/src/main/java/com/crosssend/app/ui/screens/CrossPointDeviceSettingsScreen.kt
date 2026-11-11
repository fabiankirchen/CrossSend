@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.crosssend.app.ui.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.network.CrossPointSettingItem
import com.crosssend.app.network.CrossPointStatus
import com.crosssend.app.network.RemoteDavItem
import com.crosssend.app.network.WifiConnectionState
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.crosssend.app.R
import com.crosssend.app.converter.EInkImageOptimizer

private const val MAX_SD_UPLOAD_BYTES = 150L * 1024 * 1024
private const val MAX_FONT_UPLOAD_BYTES = 30L * 1024 * 1024

private fun isSafeHttpsExternalUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("https://", ignoreCase = true) &&
        EInkImageOptimizer.isSafeExternalImageUrl(trimmed)
}

private fun extractHost(url: String): String {
    return try {
        java.net.URI(url.trim()).host?.lowercase().orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private fun isOfficialGithubHost(host: String): Boolean {
    val normalized = host.lowercase().trim()
    return normalized == "github.com" || normalized == "raw.githubusercontent.com"
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun parseSha256Map(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val map = mutableMapOf<String, String>()
    for (field in listOf("sha256", "hashes")) {
        val hashesObj = obj.optJSONObject(field) ?: continue
        val keys = hashesObj.keys()
        while (keys.hasNext()) {
            val fileKey = keys.next().trim()
            val hashVal = hashesObj.optString(fileKey, "").trim()
            if (fileKey.isNotEmpty() && hashVal.isNotEmpty()) {
                map[fileKey] = hashVal
            }
        }
    }
    return map
}

private fun parseManifestPermissions(manifestJson: String?): Pair<Boolean, List<String>> {
    if (manifestJson.isNullOrBlank()) return false to emptyList()
    return try {
        val json = JSONObject(manifestJson)
        val hasField = json.has("permissions") || json.has("capabilities")
        if (!hasField) return false to emptyList()
        val collected = linkedSetOf<String>()
        for (field in listOf("permissions", "capabilities")) {
            val arr = json.optJSONArray(field)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim().lowercase()
                    if (item.isNotEmpty()) collected.add(item)
                }
            } else if (json.has(field)) {
                val raw = json.optString(field, "")
                raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { collected.add(it) }
            }
        }
        true to collected.toList()
    } catch (_: Exception) {
        false to emptyList()
    }
}

private fun parseManifestHashes(manifestJson: String?): Map<String, String> {
    if (manifestJson.isNullOrBlank()) return emptyMap()
    return try {
        parseSha256Map(JSONObject(manifestJson))
    } catch (_: Exception) {
        emptyMap()
    }
}

data class SettingOption(
    val value: String,
    val label: String
)

data class SdPluginInfo(
    val id: String,
    val folderName: String,
    val parentDir: String = "/plugins",
    val configFile: String? = null,
    val name: String,
    val version: String,
    val description: String,
    val author: String = "",
    val isEnabled: Boolean,
    val configJson: String?,
    val pluginJs: String? = null,
    val deviceJson: String? = null,
    val manifestJson: String? = null,
    val permissions: List<String> = emptyList(),
    val hasDeclaredPermissions: Boolean = false
)

data class CatalogPlugin(
    val name: String,
    val title: String,
    val description: String,
    val author: String,
    val version: String,
    val base: String,
    val files: List<String>,
    val sha256: Map<String, String> = emptyMap()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrossPointDeviceSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val scope = rememberCoroutineScope()

    val connectionState by app.wifiManager.connectionState.collectAsState()
    val isConnected = connectionState is WifiConnectionState.ConnectedToCrossPointAp ||
            connectionState is WifiConnectionState.ConnectedOnLocalNetwork

    // 0 = Dateien (SD-Karte), 1 = Reader (Geräteeinstellungen & Plugins)
    var showSettings by remember { mutableStateOf(false) }
    var currentBaseUrl by remember { mutableStateOf(app.webDavClient.getBaseUrl()) }

    var status by remember { mutableStateOf<CrossPointStatus?>(null) }
    var settingsList by remember { mutableStateOf<List<CrossPointSettingItem>>(emptyList()) }
    var isLoadingSettings by remember { mutableStateOf(false) }

    // Plugin State
    var sdPlugins by remember { mutableStateOf<List<SdPluginInfo>>(emptyList()) }
    var isLoadingPlugins by remember { mutableStateOf(false) }
    var pluginToConfigure by remember { mutableStateOf<SdPluginInfo?>(null) }
    var deviceWebUiUrl by remember { mutableStateOf<String?>(null) }
    var activeDeviceWebView by remember { mutableStateOf<WebView?>(null) }
    // Plugin Store State (itsthisjustin/sd-plugins catalog)
    var showPluginStore by remember { mutableStateOf(false) }
    var pluginStoreSearchQuery by remember { mutableStateOf("") }
    var pluginStoreSelectedFilter by remember { mutableIntStateOf(0) }
    var catalogPlugins by remember { mutableStateOf<List<CatalogPlugin>>(emptyList()) }
    var isLoadingCatalog by remember { mutableStateOf(false) }
    var installingPluginName by remember { mutableStateOf<String?>(null) }

    // File Explorer State & Quick Address Dialog
    var currentDirectory by remember { mutableStateOf("/") }
    var remoteFiles by remember { mutableStateOf<List<RemoteDavItem>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var fileLoadError by remember { mutableStateOf<String?>(null) }
    var isUploadingFile by remember { mutableStateOf(false) }
    var isUploadingFont by remember { mutableStateOf(false) }
    var pendingFontUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFontFileName by remember { mutableStateOf("") }
    var pendingFontFamily by remember { mutableStateOf("") }
    var isDownloadingFile by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<RemoteDavItem?>(null) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var addressInputText by remember { mutableStateOf(app.wifiManager.getLanBaseUrl()) }
    var ssidInputText by remember { mutableStateOf(app.wifiManager.getApSsid()) }

    fun loadSdPlugins() {
        if (!isConnected) return
        scope.launch {
            isLoadingPlugins = true
            val plugins = mutableListOf<SdPluginInfo>()

            try {
                val searchDirs = listOf("/.crosspoint/plugins", "/plugins", "/.plugins")
                val (apiPlugins, dirListings) = coroutineScope {
                    val apiDeferred = async(Dispatchers.IO) {
                        app.deviceClient.fetchPlugins(currentBaseUrl).getOrDefault(emptyList())
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
                        if (item.isDirectory) {
                            val folderName = item.name
                            if (processedNames.add(folderName)) {
                                uniquePluginTargets.add(searchDir to folderName)
                            }
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
                                ?.let { com.crosssend.app.network.WebDavClient.sanitizeRemotePath(it) }
                                ?.takeIf { isAllowedPluginConfigPath(folderName, searchDir, it) }
                            if (configFile == null) {
                                if (folderName.equals("webdav", true)) configFile = "/.crosspoint/webdav.json"
                                else if (folderName.equals("wallabag", true)) configFile = "/.crosspoint/wallabag.json"
                                else if (folderName.equals("bookfusion", true)) configFile = "/.crosspoint/bookfusion.json"
                                else if (folderName.equals("monthwallpaper", true) || folderName.equals("month-wallpaper", true)) configFile = "/.crosspoint/month-wallpaper.json"
                            }

                            val configStr = if (configFile != null) {
                                app.webDavClient.readTextFile(configFile)
                            } else {
                                app.webDavClient.readTextFile("$searchDir/$folderName/config.json")
                            }
                            val pluginJsStr = pluginJsDeferred.await()

                            val apiMatch = apiPlugins.firstOrNull { it.name.equals(folderName, true) }
                            var name = apiMatch?.title ?: folderName.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }
                            var version = "1.0.0"
                            var description = if (apiMatch != null) {
                                "Mount: ${apiMatch.mount}"
                            } else if (resources.configuration.locales[0].language == "de") {
                                "CrossPoint SD-Plugin"
                            } else {
                                "CrossPoint SD Plugin"
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
            } catch (e: Exception) {
                // Ignore if folder does not exist yet
            }
            sdPlugins = plugins
            isLoadingPlugins = false
        }
    }

    fun openPluginConfig(plugin: SdPluginInfo) {
        scope.launch {
            var enriched = plugin
            if (enriched.pluginJs.isNullOrBlank() || enriched.configJson == null) {
                val pJs = withContext(Dispatchers.IO) {
                    app.webDavClient.readTextFile("${enriched.parentDir}/${enriched.folderName}/plugin.js")
                }
                val cfg = withContext(Dispatchers.IO) {
                    if (enriched.configFile != null) {
                        app.webDavClient.readTextFile(enriched.configFile!!)
                    } else {
                        app.webDavClient.readTextFile("${enriched.parentDir}/${enriched.folderName}/config.json")
                    }
                }
                enriched = enriched.copy(
                    pluginJs = if (!pJs.isNullOrBlank()) pJs else enriched.pluginJs,
                    configJson = cfg ?: enriched.configJson
                )
            }
            pluginToConfigure = enriched
        }
    }

    fun loadCatalog() {
        scope.launch {
            isLoadingCatalog = true
            try {
                val client = app.wifiManager.getInternetOkHttpClientBuilder()
                    .addInterceptor { chain ->
                        val reqUrl = chain.request().url.toString()
                        if (!isSafeHttpsExternalUrl(reqUrl)) {
                            throw java.io.IOException("Unsafe or non-HTTPS URL")
                        }
                        val resp = chain.proceed(chain.request())
                        if (!isSafeHttpsExternalUrl(resp.request.url.toString())) {
                            resp.close()
                            throw java.io.IOException("Unsafe or non-HTTPS redirect")
                        }
                        resp
                    }
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("https://raw.githubusercontent.com/itsthisjustin/sd-plugins/main/catalog.json")
                    .get()
                    .build()
                val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                val list = mutableListOf<CatalogPlugin>()
                resp.use { r ->
                    if (r.isSuccessful) {
                        val bodyStr = r.body?.let { EInkImageOptimizer.readBoundedString(it, 2L * 1024 * 1024) } ?: ""
                        val json = JSONObject(bodyStr)
                        val arr = json.getJSONArray("plugins")
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val baseUrl = obj.getString("base").trim()
                            if (!isSafeHttpsExternalUrl(baseUrl)) continue
                            val filesArr = obj.optJSONArray("files")
                            val filesList = mutableListOf<String>()
                            if (filesArr != null) {
                                for (j in 0 until filesArr.length()) {
                                    filesList.add(filesArr.getString(j))
                                }
                            }
                            list.add(
                                CatalogPlugin(
                                    name = obj.getString("name"),
                                    title = obj.optString("title", obj.getString("name")),
                                    description = obj.optString("description", ""),
                                    author = obj.optString("author", ""),
                                    version = obj.optString("version", "1.0.0"),
                                    base = baseUrl,
                                    files = filesList,
                                    sha256 = parseSha256Map(obj)
                                )
                            )
                        }
                    }
                }
                val isDeLang = resources.configuration.locales[0].language == "de"
                // Ensure "Protected Content" is always present in catalog
                if (list.none { it.name.equals("protected-content", true) }) {
                    list.add(
                        0,
                        CatalogPlugin(
                            name = "protected-content",
                            title = "Protected Content",
                            description = if (isDeLang) {
                                "Fulfill geschützte Bücher (Adobe / ByteBooks) direkt über das Smartphone ohne Reader-WLAN."
                            } else {
                                "Fulfill protected books (Adobe / ByteBooks) directly via smartphone without reader Wi-Fi."
                            },
                            author = "Diirge",
                            version = "1.0.5",
                            base = "https://protected-content-host-production.up.railway.app/",
                            files = listOf("manifest.json", "plugin.js")
                        )
                    )
                }
                catalogPlugins = list
            } catch (e: Exception) {
                val isDeLang = resources.configuration.locales[0].language == "de"
                // Fallback list with Protected Content even if GitHub catalog fails
                val fallbackList = mutableListOf(
                    CatalogPlugin(
                        name = "protected-content",
                        title = "Protected Content",
                        description = if (isDeLang) {
                            "Fulfill geschützte Bücher (Adobe / ByteBooks) direkt über das Smartphone ohne Reader-WLAN."
                        } else {
                            "Fulfill protected books (Adobe / ByteBooks) directly via smartphone without reader Wi-Fi."
                        },
                        author = "Diirge",
                        version = "1.0.5",
                        base = "https://protected-content-host-production.up.railway.app/",
                        files = listOf("manifest.json", "plugin.js")
                    )
                )
                catalogPlugins = fallbackList
            } finally {
                isLoadingCatalog = false
            }
        }
    }

    fun installCatalogPlugin(plugin: CatalogPlugin) {
        scope.launch {
            val safePluginName = plugin.name.trim()
            if (safePluginName.isBlank() ||
                safePluginName.contains("..") ||
                safePluginName.contains("/") ||
                safePluginName.contains("\\") ||
                safePluginName.lowercase() in RESERVED_PLUGIN_NAMES
            ) {
                Toast.makeText(context, resources.getString(R.string.dev_invalid_plugin_name), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val cleanBase = plugin.base.trim()
            if (!isSafeHttpsExternalUrl(cleanBase)) {
                Toast.makeText(context, resources.getString(R.string.dev_plugin_unsafe_url), Toast.LENGTH_LONG).show()
                return@launch
            }
            val existingParentDir = sdPlugins.firstOrNull { it.folderName.equals(safePluginName, ignoreCase = true) }?.parentDir
            installingPluginName = safePluginName
            try {
                val ok = withContext(Dispatchers.IO) {
                    val client = app.wifiManager.getInternetOkHttpClientBuilder()
                        .addInterceptor { chain ->
                            val reqUrl = chain.request().url.toString()
                            if (!isSafeHttpsExternalUrl(reqUrl)) {
                                throw java.io.IOException("Unsafe or non-HTTPS URL")
                            }
                            val resp = chain.proceed(chain.request())
                            if (!isSafeHttpsExternalUrl(resp.request.url.toString())) {
                                resp.close()
                                throw java.io.IOException("Unsafe or non-HTTPS redirect")
                            }
                            resp
                        }
                        .build()

                    val downloadedFiles = mutableListOf<Triple<String, String, String>>()
                    var manifestContent: String? = null

                    for (fileName in plugin.files) {
                        val cleanFile = fileName.trim()
                        if (cleanFile.isBlank() || cleanFile.contains("..") || cleanFile.contains("/") || cleanFile.contains("\\")) continue
                        val fileUrl = "${cleanBase.trimEnd('/')}/$cleanFile"
                        if (!isSafeHttpsExternalUrl(fileUrl)) {
                            throw SecurityException("Unsafe or non-HTTPS plugin file URL")
                        }
                        val req = okhttp3.Request.Builder().url(fileUrl).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                throw java.io.IOException("HTTP ${resp.code} for $cleanFile")
                            }
                            val bytes = resp.body?.source()?.let { src ->
                                src.request(5L * 1024 * 1024 + 1)
                                if (src.buffer.size > 5L * 1024 * 1024) {
                                    throw java.io.IOException("Plugin file exceeds 5 MB limit")
                                }
                                src.buffer.readByteArray()
                            } ?: ByteArray(0)
                            val actualSha = sha256Hex(bytes)
                            val content = String(bytes, Charsets.UTF_8)
                            if (cleanFile.equals("manifest.json", ignoreCase = true)) {
                                manifestContent = content
                            }
                            downloadedFiles.add(Triple(cleanFile, content, actualSha))
                        }
                    }

                    val expectedHashes = buildMap {
                        putAll(plugin.sha256)
                        putAll(parseManifestHashes(manifestContent))
                    }
                    for ((cleanFile, _, actualSha) in downloadedFiles) {
                        val expectedSha = expectedHashes[cleanFile]
                        if (!expectedSha.isNullOrBlank() && !actualSha.equals(expectedSha.trim(), ignoreCase = true)) {
                            throw SecurityException(resources.getString(R.string.dev_plugin_checksum_mismatch, cleanFile))
                        }
                    }

                    val preferredRoot = existingParentDir ?: run {
                        val hasRootPlugins = app.webDavClient.listDirectoryResult("/plugins").isSuccess
                        val hasCrosspointPlugins = app.webDavClient.listDirectoryResult("/.crosspoint/plugins").isSuccess
                        when {
                            hasRootPlugins -> "/plugins"
                            hasCrosspointPlugins -> "/.crosspoint/plugins"
                            else -> "/plugins"
                        }
                    }
                    val targetDir = "$preferredRoot/$safePluginName"
                    app.webDavClient.ensureDirectoryExists(targetDir)

                    for ((cleanFile, content, _) in downloadedFiles) {
                        app.webDavClient.writeTextFile("$targetDir/$cleanFile", content)
                    }
                    true
                }
                if (ok) {
                    Toast.makeText(context, resources.getString(R.string.dev_plugin_installed_success, plugin.title), Toast.LENGTH_SHORT).show()
                    loadSdPlugins()
                } else {
                    Toast.makeText(context, resources.getString(R.string.dev_plugin_install_failed, plugin.title), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.dev_plugin_install_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                installingPluginName = null
            }
        }
    }

    fun loadDeviceData() {
        if (!isConnected) return
        scope.launch {
            isLoadingSettings = true
            val statusRes = app.deviceClient.fetchStatus(currentBaseUrl)
            if (statusRes.isSuccess) {
                val st = statusRes.getOrNull()
                status = st
                val prefs = context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
                val profile = com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(st?.deviceModel)
                if (com.crosssend.app.network.CrossPointDeviceClient.isSpecificDetectedModel(st?.deviceModel)) {
                    prefs.edit().putString("device_model", profile.id).apply()
                }
            }
            val settingsRes = app.deviceClient.fetchSettings(currentBaseUrl)
            if (settingsRes.isSuccess) {
                settingsList = settingsRes.getOrDefault(emptyList())
            }
            isLoadingSettings = false
            loadSdPlugins()
        }
    }

    fun togglePlugin(plugin: SdPluginInfo, enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val manifestPath = "${plugin.parentDir}/${plugin.folderName}/manifest.json"
                val existingManifest = app.webDavClient.readTextFile(manifestPath)
                val json = if (!existingManifest.isNullOrBlank()) {
                    try { JSONObject(existingManifest) } catch (e: Exception) { JSONObject() }
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
            val statusStr = if (enabled) resources.getString(R.string.dev_plugin_enabled) else resources.getString(R.string.dev_plugin_disabled)
            Toast.makeText(context, resources.getString(R.string.dev_plugin_status_toggled, plugin.name, statusStr), Toast.LENGTH_SHORT).show()
            loadSdPlugins()
        }
    }

    fun initPluginsDirectory() {
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.ensureDirectoryExists("/plugins")
            }
            if (success) {
                Toast.makeText(context, resources.getString(R.string.dev_plugins_folder_ready), Toast.LENGTH_SHORT).show()
                loadSdPlugins()
            } else {
                Toast.makeText(context, resources.getString(R.string.dev_plugins_folder_create_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun loadDirectory(path: String) {
        if (!isConnected) return
        scope.launch {
            isLoadingFiles = true
            fileLoadError = null
            val result = withContext(Dispatchers.IO) {
                app.webDavClient.listDirectoryResult(path)
            }
            if (result.isSuccess) {
                remoteFiles = result.getOrDefault(emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                currentDirectory = path
                fileLoadError = null
            } else {
                fileLoadError = result.exceptionOrNull()?.message ?: resources.getString(R.string.wifi_error_connection)
            }
            isLoadingFiles = false
        }
    }

    // File Picker for Uploading to SD Card
    val uploadPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (!com.crosssend.app.converter.DocumentProcessor.isSafeSharedUri(context, uri)) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                isUploadingFile = true
                try {
                    var uploadFileName = "upload_${System.currentTimeMillis()}"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) {
                                uploadFileName = cursor.getString(nameIdx)
                            }
                        }
                    }
                    uploadFileName = uploadFileName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")
                    val safeFileName = File(uploadFileName).name.ifBlank { "upload_${System.currentTimeMillis()}.bin" }

                    val tempFile = ensureInsideDirectory(context.cacheDir, File(context.cacheDir, safeFileName))
                    val success = try {
                        withContext(Dispatchers.IO) {
                            val inputStream = context.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("Cannot open selected file")
                            inputStream.use { input ->
                                tempFile.outputStream().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    var totalRead = 0L
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        totalRead += read
                                        if (totalRead > MAX_SD_UPLOAD_BYTES) {
                                            throw java.io.IOException("File exceeds 150 MB upload limit")
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }

                        val remoteTarget = "${currentDirectory.trimEnd('/')}/$safeFileName"
                        withContext(Dispatchers.IO) {
                            app.webDavClient.uploadFile(tempFile, remoteTarget)
                        }
                    } finally {
                        tempFile.delete()
                    }

                    if (success) {
                        Toast.makeText(context, resources.getString(R.string.dev_file_upload_success, safeFileName), Toast.LENGTH_SHORT).show()
                        loadDirectory(currentDirectory)
                    } else {
                        Toast.makeText(context, resources.getString(R.string.dev_file_upload_failed, safeFileName), Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, resources.getString(R.string.dev_file_upload_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                } finally {
                    isUploadingFile = false
                }
            }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            if (!com.crosssend.app.converter.DocumentProcessor.isSafeSharedUri(context, uri)) {
                return@rememberLauncherForActivityResult
            }
            var fontFileName = "font_${System.currentTimeMillis()}.ttf"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        fontFileName = cursor.getString(nameIdx)
                    }
                }
            }
            val safeFontName = File(fontFileName.substringAfterLast('/').substringAfterLast('\\').replace("..", "_")).name
            val lower = safeFontName.lowercase()
            if (!lower.endsWith(".cpfont") &&
                !lower.endsWith(".ttf") &&
                !lower.endsWith(".otf") &&
                !lower.endsWith(".ttc")
            ) {
                Toast.makeText(context, resources.getString(R.string.dev_font_invalid_format), Toast.LENGTH_LONG).show()
            } else {
                pendingFontUri = uri
                pendingFontFileName = safeFontName
                pendingFontFamily = safeFontName.substringBeforeLast('.')
            }
        }
    }

    fun uploadSelectedFont(uri: Uri, fileName: String, familyName: String) {
        scope.launch {
            isUploadingFont = true
            try {
                val safeFontName = File(fileName).name.ifBlank { "font_${System.currentTimeMillis()}.ttf" }
                val tempFile = ensureInsideDirectory(context.cacheDir, File(context.cacheDir, safeFontName))
                val res = try {
                    withContext(Dispatchers.IO) {
                        val inputStream = context.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("Cannot open selected font")
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var totalRead = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    totalRead += read
                                    if (totalRead > MAX_FONT_UPLOAD_BYTES) {
                                        throw java.io.IOException("Font file exceeds 30 MB limit")
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    app.deviceClient.uploadFont(currentBaseUrl, familyName, tempFile)
                } finally {
                    tempFile.delete()
                }
                if (res.isSuccess) {
                    Toast.makeText(context, resources.getString(R.string.dev_font_upload_success, familyName.ifBlank { safeFontName }), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, resources.getString(R.string.dev_font_upload_failed, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.dev_font_upload_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                isUploadingFont = false
            }
        }
    }

    fun downloadRemoteItem(item: RemoteDavItem) {
        scope.launch {
            isDownloadingFile = true
            val safeName = File(item.name).name.ifBlank { "download.bin" }
            Toast.makeText(context, resources.getString(R.string.dev_file_downloading, safeName), Toast.LENGTH_SHORT).show()
            try {
                val tempFile = ensureInsideDirectory(context.cacheDir, File(context.cacheDir, safeName))
                val success = withContext(Dispatchers.IO) {
                    app.webDavClient.downloadFile(item.path, tempFile)
                }

                if (success && tempFile.exists()) {
                    val saved = withContext(Dispatchers.IO) {
                        saveToPublicDownloads(context, safeName, tempFile)
                    }
                    tempFile.delete()

                    if (saved) {
                        Toast.makeText(context, resources.getString(R.string.dev_file_download_saved, safeName), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, resources.getString(R.string.dev_file_download_save_failed), Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, resources.getString(R.string.dev_file_download_failed, safeName), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.dev_file_download_error, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                isDownloadingFile = false
            }
        }
    }

    LaunchedEffect(isConnected, currentBaseUrl, showSettings) {
        if (isConnected) {
            if (showSettings) {
                loadDeviceData()
            } else {
                loadDirectory(currentDirectory)
            }
        }
    }

    var backProgress by remember { mutableFloatStateOf(0f) }

    // 1. Predictive Back for Plugin Config
    PredictiveBackHandler(enabled = pluginToConfigure != null) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            pluginToConfigure = null
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    // 2. Predictive Back for Device Web-UI
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl != null) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            if (activeDeviceWebView?.canGoBack() == true) {
                activeDeviceWebView?.goBack()
            } else {
                deviceWebUiUrl = null
            }
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    // 3. Predictive Back for Plugin Store
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl == null && showPluginStore) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            when {
                pluginStoreSearchQuery.isNotEmpty() -> pluginStoreSearchQuery = ""
                pluginStoreSelectedFilter != 0 -> pluginStoreSelectedFilter = 0
                else -> showPluginStore = false
            }
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    // 4. Predictive Back from Settings back to SD-Card Explorer
    PredictiveBackHandler(enabled = pluginToConfigure == null && deviceWebUiUrl == null && !showPluginStore && !showAddressDialog && showSettings) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            showSettings = false
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    // 5. Predictive Back inside subfolders of SD-Card Explorer
    PredictiveBackHandler(
        enabled = pluginToConfigure == null && deviceWebUiUrl == null && !showPluginStore && !showAddressDialog && !showSettings && currentDirectory != "/" && currentDirectory.isNotBlank()
    ) { progress: Flow<BackEventCompat> ->
        try {
            progress.collect { event -> backProgress = event.progress }
            backProgress = 0f
            val parent = currentDirectory.trimEnd('/').substringBeforeLast('/', "")
            val targetParent = if (parent.isBlank()) "/" else parent
            loadDirectory(targetParent)
        } catch (e: CancellationException) {
            backProgress = 0f
        }
    }

    val isSubpageActive = pluginToConfigure != null || deviceWebUiUrl != null || showPluginStore
    val effectsFloatSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatialOffsetSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val title = when {
                            pluginToConfigure != null -> pluginToConfigure!!.name
                            deviceWebUiUrl != null -> stringResource(R.string.dev_title_web_ui)
                            showPluginStore -> stringResource(R.string.dev_title_plugin_store)
                            showSettings -> stringResource(R.string.dev_title_device_settings)
                            else -> stringResource(R.string.dev_title_sd_card)
                        }
                        val subtitle = when {
                            pluginToConfigure != null -> stringResource(R.string.dev_title_plugin_config, pluginToConfigure!!.folderName)
                            deviceWebUiUrl != null -> deviceWebUiUrl!!.removePrefix("http://")
                            showPluginStore -> stringResource(R.string.dev_title_store_catalog)
                            showSettings -> if (isConnected) currentBaseUrl.removePrefix("http://") else stringResource(R.string.status_disconnected)
                            else -> if (currentDirectory.isBlank() || currentDirectory == "/") "/" else currentDirectory
                        }
                        AnimatedContent(
                            targetState = title to subtitle,
                            transitionSpec = {
                                (fadeIn(animationSpec = effectsFloatSpec) +
                                        slideInVertically(animationSpec = spatialOffsetSpec) { it / 3 })
                                    .togetherWith(
                                        fadeOut(animationSpec = effectsFloatSpec) +
                                                slideOutVertically(animationSpec = spatialOffsetSpec) { -it / 3 }
                                    )
                            },
                            label = "TopAppBarTitle"
                        ) { (currentTitle, currentSubtitle) ->
                            Column {
                                Text(
                                    text = currentTitle,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentSubtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when {
                                pluginToConfigure != null -> pluginToConfigure = null
                                deviceWebUiUrl != null -> {
                                    if (activeDeviceWebView?.canGoBack() == true) {
                                        activeDeviceWebView?.goBack()
                                    } else {
                                        deviceWebUiUrl = null
                                    }
                                }
                                showPluginStore -> {
                                    pluginStoreSearchQuery = ""
                                    pluginStoreSelectedFilter = 0
                                    showPluginStore = false
                                }
                                showSettings -> showSettings = false
                                currentDirectory != "/" && currentDirectory.isNotBlank() -> {
                                    val parent = currentDirectory.trimEnd('/').substringBeforeLast('/', "")
                                    val targetParent = if (parent.isBlank()) "/" else parent
                                    loadDirectory(targetParent)
                                }
                                else -> onNavigateBack()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.dev_action_back)
                            )
                        }
                    },
                    actions = {
                        when {
                            pluginToConfigure != null -> {
                                IconButton(onClick = {
                                    openPluginConfig(pluginToConfigure!!)
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_reload_plugin))
                                }
                            }
                            deviceWebUiUrl != null -> {
                                // Clean TopAppBar in Web-UI
                            }
                            showPluginStore -> {
                                IconButton(onClick = { loadCatalog() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh_catalog))
                                }
                            }
                            showSettings -> {
                                IconButton(onClick = { loadDeviceData() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh))
                                }
                            }
                            else -> {
                                IconButton(onClick = { loadDirectory(currentDirectory) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dev_action_refresh))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (isConnected && !isSubpageActive) {
                    PrimaryTabRow(
                        selectedTabIndex = if (showSettings) 1 else 0,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = !showSettings,
                            onClick = {
                                if (showSettings) {
                                    showSettings = false
                                }
                            },
                            text = { Text(stringResource(R.string.dev_tab_files), fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = showSettings,
                            onClick = {
                                if (!showSettings) {
                                    showSettings = true
                                    if (settingsList.isEmpty()) {
                                        loadDeviceData()
                                    }
                                }
                            },
                            text = { Text(stringResource(R.string.dev_tab_settings), fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showSettings && !isSubpageActive && isConnected) {
                ExtendedFloatingActionButton(
                    onClick = { uploadPickerLauncher.launch("*/*") },
                    icon = {
                        if (isUploadingFile) {
                            LoadingIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                        }
                    },
                    text = {
                        Text(if (isUploadingFile) stringResource(R.string.dev_uploading) else stringResource(R.string.dev_upload_file), fontWeight = FontWeight.SemiBold)
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    ) { paddingValues ->
        val activeDestinationKey = when {
            pluginToConfigure != null -> "PLUGIN_CONFIG"
            deviceWebUiUrl != null -> "WEB_UI"
            showPluginStore -> "PLUGIN_STORE"
            showSettings -> "SETTINGS"
            else -> "FILES"
        }

        AnimatedContent(
            targetState = activeDestinationKey,
            transitionSpec = {
                (fadeIn(effectsFloatSpec) + slideInHorizontally(spatialOffsetSpec) { it / 10 })
                    .togetherWith(
                        fadeOut(effectsFloatSpec) + slideOutHorizontally(spatialOffsetSpec) { -it / 10 }
                    )
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .graphicsLayer {
                    val scale = 1f - (backProgress * 0.03f)
                    scaleX = scale
                    scaleY = scale
                },
            label = "DeviceDestinationTransition"
        ) { destination ->
            when (destination) {
                "FILES" -> {
                    NativeFileExplorerView(
                        currentPath = currentDirectory,
                        items = remoteFiles,
                        isLoading = isLoadingFiles,
                        fileLoadError = fileLoadError,
                        isDownloading = isDownloadingFile,
                        isConnected = isConnected,
                        connectionState = connectionState,
                        onNavigateTo = { path -> loadDirectory(path) },
                        onDownloadRequested = { item -> downloadRemoteItem(item) },
                        onDeleteRequested = { item -> itemToDelete = item },
                        onConnect = {
                            scope.launch {
                                app.wifiManager.connectOrDiscover(app.webDavClient)
                                currentBaseUrl = app.webDavClient.getBaseUrl()
                            }
                        },
                        onDirectHotspotConnect = {
                            app.wifiManager.connectToCrossPointAp()
                        },
                        onCancel = {
                            app.wifiManager.cancelConnectionAttempt()
                        },
                        onRetry = { loadDirectory(currentDirectory) },
                        onChangeAddress = {
                            addressInputText = app.wifiManager.getLanBaseUrl()
                            ssidInputText = app.wifiManager.getApSsid()
                            showAddressDialog = true
                        }
                    )
                }

                "SETTINGS" -> {
                    NativeSettingsView(
                        isConnected = isConnected,
                        connectionState = connectionState,
                        currentBaseUrl = currentBaseUrl,
                        onUrlChanged = { newUrl ->
                            currentBaseUrl = newUrl
                            app.wifiManager.setLanBaseUrl(newUrl)
                            app.webDavClient.updateBaseUrl(newUrl)
                        },
                        status = status,
                        settingsList = settingsList,
                        isLoading = isLoadingSettings,
                        sdPlugins = sdPlugins,
                        isLoadingPlugins = isLoadingPlugins,
                        onTogglePlugin = { plugin, enabled -> togglePlugin(plugin, enabled) },
                        onConfigurePlugin = { plugin -> openPluginConfig(plugin) },
                        onOpenDeviceWebUi = { deviceWebUiUrl = currentBaseUrl },
                        onOpenPluginStore = {
                            showPluginStore = true
                            if (catalogPlugins.isEmpty()) {
                                loadCatalog()
                            }
                        },
                        onInitPluginsDir = { initPluginsDirectory() },
                        onNavigateToExplorer = { path ->
                            showSettings = false
                            loadDirectory(path)
                        },
                        onUpdateSetting = { key, newValue ->
                            // Optimistic UI update
                            settingsList = settingsList.map { item ->
                                if (item.key == key) item.copy(value = newValue) else item
                            }
                            scope.launch {
                                val res = app.deviceClient.updateSetting(currentBaseUrl, key, newValue)
                                if (res.isSuccess) {
                                    Toast.makeText(context, resources.getString(R.string.dev_setting_saved, key), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, resources.getString(R.string.dev_setting_save_error, res.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                                    loadDeviceData()
                                }
                            }
                        },
                        onConnect = {
                            scope.launch {
                                app.wifiManager.connectOrDiscover(app.webDavClient)
                                currentBaseUrl = app.webDavClient.getBaseUrl()
                            }
                        },
                        onDirectHotspotConnect = {
                            app.wifiManager.connectToCrossPointAp()
                        },
                        onCancel = {
                            app.wifiManager.cancelConnectionAttempt()
                        },
                        onChangeAddress = {
                            addressInputText = app.wifiManager.getLanBaseUrl()
                            ssidInputText = app.wifiManager.getApSsid()
                            showAddressDialog = true
                        },
                        isUploadingFont = isUploadingFont,
                        onUploadFont = { fontPickerLauncher.launch("*/*") }
                    )
                }

                "PLUGIN_STORE" -> {
                    PluginStoreSubpage(
                        catalogPlugins = catalogPlugins,
                        sdPlugins = sdPlugins,
                        isLoadingCatalog = isLoadingCatalog,
                        installingPluginName = installingPluginName,
                        isConnected = isConnected,
                        searchQuery = pluginStoreSearchQuery,
                        onSearchQueryChange = { pluginStoreSearchQuery = it },
                        selectedFilter = pluginStoreSelectedFilter,
                        onSelectedFilterChange = { pluginStoreSelectedFilter = it },
                        onRefreshCatalog = { loadCatalog() },
                        onInstallPlugin = { catPlugin -> installCatalogPlugin(catPlugin) },
                        onConfigurePlugin = { plugin -> openPluginConfig(plugin) }
                    )
                }

                "PLUGIN_CONFIG" -> {
                    pluginToConfigure?.let { plugin ->
                        PluginConfigSubpage(
                            plugin = plugin,
                            currentBaseUrl = currentBaseUrl,
                            deviceStatus = status,
                            onSaveConfig = { newConfigJson ->
                                scope.launch {
                                    val configPath = plugin.configFile ?: "${plugin.parentDir}/${plugin.folderName}/config.json"
                                    val ok = withContext(Dispatchers.IO) {
                                        app.webDavClient.writeTextFile(configPath, newConfigJson)
                                    }
                                    if (ok) {
                                        Toast.makeText(context, resources.getString(R.string.dev_settings_saved), Toast.LENGTH_SHORT).show()
                                        pluginToConfigure = plugin.copy(configJson = newConfigJson)
                                        loadSdPlugins()
                                    } else {
                                        Toast.makeText(context, resources.getString(R.string.dev_settings_save_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }

                "WEB_UI" -> {
                    deviceWebUiUrl?.let { url ->
                        DeviceWebUiSubpage(
                            webUiUrl = url,
                            onWebViewCreated = { activeDeviceWebView = it }
                        )
                    }
                }
            }
        }
    }

    // Vector Font Family Name Dialog
    pendingFontUri?.let { fontUri ->
        AlertDialog(
            onDismissRequest = { pendingFontUri = null },
            title = { Text(stringResource(R.string.dev_font_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pendingFontFamily,
                    onValueChange = { pendingFontFamily = it },
                    label = { Text(stringResource(R.string.dev_font_family_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetUri = fontUri
                        val fileName = pendingFontFileName
                        val family = pendingFontFamily.trim().ifBlank { fileName.substringBeforeLast('.') }
                        pendingFontUri = null
                        uploadSelectedFont(targetUri, fileName, family)
                    }
                ) {
                    Text(stringResource(R.string.dev_btn_upload_font))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFontUri = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Quick Address & Wi-Fi Change Dialog
    if (showAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text(stringResource(R.string.dev_dialog_wifi_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.dev_dialog_wifi_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = ssidInputText,
                        onValueChange = { ssidInputText = it },
                        label = { Text(stringResource(R.string.dev_label_wifi_ssid)) },
                        placeholder = { Text(stringResource(R.string.dev_placeholder_wifi_ssid)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = ssidInputText == "Cross",
                            onClick = { ssidInputText = "Cross" },
                            label = { Text(stringResource(R.string.conn_chip_cross_universal)) }
                        )
                        FilterChip(
                            selected = ssidInputText == "CrossPlay",
                            onClick = { ssidInputText = "CrossPlay" },
                            label = { Text("CrossPlay") }
                        )
                        FilterChip(
                            selected = ssidInputText == "CrossPoint",
                            onClick = { ssidInputText = "CrossPoint" },
                            label = { Text("CrossPoint") }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = addressInputText,
                        onValueChange = { addressInputText = it },
                        label = { Text(stringResource(R.string.dev_label_device_address)) },
                        placeholder = { Text(stringResource(R.string.dev_placeholder_device_address)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = addressInputText.contains("crossplay.local"),
                            onClick = { addressInputText = "http://crossplay.local" },
                            label = { Text("crossplay.local") }
                        )
                        FilterChip(
                            selected = addressInputText.contains("crosspoint.local"),
                            onClick = { addressInputText = "http://crosspoint.local" },
                            label = { Text("crosspoint.local") }
                        )
                        FilterChip(
                            selected = addressInputText.contains("192.168.4.1"),
                            onClick = { addressInputText = "http://192.168.4.1" },
                            label = { Text(stringResource(R.string.conn_chip_ap_ip)) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cleanSsid = ssidInputText.trim().ifBlank { "Cross" }
                    app.wifiManager.setApSsid(cleanSsid)

                    val clean = addressInputText.trim()
                    val target = if (!clean.startsWith("http://") && !clean.startsWith("https://")) "http://$clean" else clean
                    currentBaseUrl = target
                    app.wifiManager.setLanBaseUrl(target)
                    app.webDavClient.updateBaseUrl(target)
                    showAddressDialog = false
                    if (showSettings) loadDeviceData() else loadDirectory(currentDirectory)
                }) {
                    Text(stringResource(R.string.dev_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Delete Confirmation Dialog for File Explorer
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.dev_delete_dialog_title)) },
            text = { Text(stringResource(R.string.dev_delete_dialog_msg, item.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                app.webDavClient.deleteFile(item.path)
                            }
                            if (success) {
                                Toast.makeText(context, resources.getString(R.string.dev_item_deleted, item.name), Toast.LENGTH_SHORT).show()
                                loadDirectory(currentDirectory)
                            } else {
                                Toast.makeText(context, resources.getString(R.string.dev_delete_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                        itemToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.dev_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun CrossPointConnectionCard(
    connectionState: WifiConnectionState,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onChangeAddress: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnecting = connectionState is WifiConnectionState.ConnectingToAp ||
            connectionState is WifiConnectionState.SearchingOnLan

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.dev_not_connected_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.dev_not_connected_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isConnecting) {
                val statusText = if (connectionState is WifiConnectionState.SearchingOnLan) {
                    stringResource(R.string.dev_status_searching_lan)
                } else {
                    stringResource(R.string.dev_status_connecting_hotspot)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_auto_connect))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDirectHotspotConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.dev_btn_direct_hotspot))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            TextButton(
                onClick = onChangeAddress,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.dev_btn_customize_connection))
            }
        }
    }
}

@Composable
private fun NativeFileExplorerView(
    currentPath: String,
    items: List<RemoteDavItem>,
    isLoading: Boolean,
    fileLoadError: String? = null,
    isDownloading: Boolean,
    isConnected: Boolean,
    connectionState: WifiConnectionState,
    onNavigateTo: (String) -> Unit,
    onDownloadRequested: (RemoteDavItem) -> Unit,
    onDeleteRequested: (RemoteDavItem) -> Unit,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit = {},
    onChangeAddress: () -> Unit = {}
) {
    if (!isConnected) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            CrossPointConnectionCard(
                connectionState = connectionState,
                onConnect = onConnect,
                onDirectHotspotConnect = onDirectHotspotConnect,
                onChangeAddress = onChangeAddress,
                onCancel = onCancel
            )
        }
        return
    }

    // Sort items: folders first, then files alphabetically
    val sortedItems = remember(items) {
        items.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Item Counter & Directory Summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val dirCount = sortedItems.count { it.isDirectory }
            val fileCount = sortedItems.count { !it.isDirectory }
            Text(
                text = buildString {
                    if (dirCount > 0) append(pluralStringResource(R.plurals.dev_files_summary_folders, dirCount, dirCount))
                    if (dirCount > 0 && fileCount > 0) append(" · ")
                    if (fileCount > 0) append(pluralStringResource(R.plurals.dev_files_summary_files, fileCount, fileCount))
                    if (dirCount == 0 && fileCount == 0 && !isLoading && fileLoadError == null) append(stringResource(R.string.dev_files_summary_empty))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dev_files_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (fileLoadError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.dev_connection_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.dev_connection_failed_desc, fileLoadError ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = onChangeAddress,
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(stringResource(R.string.dev_btn_adjust_address))
                            }
                            FilledTonalButton(
                                onClick = onRetry,
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                                    contentColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(stringResource(R.string.dev_btn_retry))
                            }
                        }
                    }
                }
            }
        } else if (sortedItems.isEmpty()) {
            val isSubfolder = currentPath != "/" && currentPath.isNotBlank()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isSubfolder) {
                    val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                    val targetParent = if (parent.isBlank()) "/" else parent
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clickable { onNavigateTo(targetParent) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "..",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.dev_parent_folder),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isSubfolder) stringResource(R.string.dev_folder_empty_title) else stringResource(R.string.dev_no_files_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dev_folder_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            val isSubfolder = currentPath != "/" && currentPath.isNotBlank()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (isSubfolder) {
                    val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                    val targetParent = if (parent.isBlank()) "/" else parent
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onNavigateTo(targetParent) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "..",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.dev_parent_folder),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                items(sortedItems, key = { it.path }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (item.isDirectory) {
                                    onNavigateTo(item.path)
                                }
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when {
                                            item.isDirectory && (item.name.equals("plugins", true) || currentPath.contains("plugins", true)) -> MaterialTheme.colorScheme.tertiaryContainer
                                            item.isDirectory -> MaterialTheme.colorScheme.primaryContainer
                                            item.name.endsWith(".epub", true) || item.name.endsWith(".pdf", true) -> MaterialTheme.colorScheme.tertiaryContainer
                                            item.name.endsWith(".bmp", true) || item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true) -> MaterialTheme.colorScheme.secondaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        item.isDirectory && (item.name.equals("plugins", true) || currentPath.contains("plugins", true)) -> Icons.Default.Extension
                                        item.isDirectory -> Icons.Default.Folder
                                        item.name.endsWith(".epub", true) || item.name.endsWith(".pdf", true) -> Icons.AutoMirrored.Filled.MenuBook
                                        item.name.endsWith(".bmp", true) || item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true) -> Icons.Default.Image
                                        else -> Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        item.isDirectory && (item.name.equals("plugins", true) || currentPath.contains("plugins", true)) -> MaterialTheme.colorScheme.onTertiaryContainer
                                        item.isDirectory -> MaterialTheme.colorScheme.onPrimaryContainer
                                        item.name.endsWith(".epub", true) || item.name.endsWith(".pdf", true) -> MaterialTheme.colorScheme.onTertiaryContainer
                                        item.name.endsWith(".bmp", true) || item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true) -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (item.isDirectory) stringResource(R.string.dev_item_folder) else formatFileSize(item.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!item.isDirectory) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onDownloadRequested(item) },
                                        enabled = !isDownloading,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = stringResource(R.string.dev_action_download),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { onDeleteRequested(item) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.dev_action_delete),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeSettingsView(
    isConnected: Boolean,
    connectionState: WifiConnectionState,
    currentBaseUrl: String,
    onUrlChanged: (String) -> Unit,
    status: CrossPointStatus?,
    settingsList: List<CrossPointSettingItem>,
    isLoading: Boolean,
    sdPlugins: List<SdPluginInfo>,
    isLoadingPlugins: Boolean,
    onTogglePlugin: (SdPluginInfo, Boolean) -> Unit,
    onConfigurePlugin: (SdPluginInfo) -> Unit,
    onOpenDeviceWebUi: () -> Unit = {},
    onOpenPluginStore: () -> Unit,
    onInitPluginsDir: () -> Unit,
    onNavigateToExplorer: (String) -> Unit,
    onUpdateSetting: (String, Any) -> Unit,
    onConnect: () -> Unit,
    onDirectHotspotConnect: () -> Unit,
    onCancel: () -> Unit,
    onChangeAddress: () -> Unit = {},
    isUploadingFont: Boolean = false,
    onUploadFont: () -> Unit = {}
) {
    val isDe = LocalLocale.current.platformLocale.language == "de"
    // State for interactive option pickers & text editors (100% bug-free native dialogs!)
    var settingToPick by remember { mutableStateOf<Pair<CrossPointSettingItem, List<SettingOption>>?>(null) }
    var settingToEdit by remember { mutableStateOf<CrossPointSettingItem?>(null) }
    var editValueText by remember { mutableStateOf("") }
    var showPasswordText by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isConnected) {
            CrossPointConnectionCard(
                connectionState = connectionState,
                onConnect = onConnect,
                onDirectHotspotConnect = onDirectHotspotConnect,
                onChangeAddress = onChangeAddress,
                onCancel = onCancel
            )
        } else {
            // Live Device Status & Connection Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_status_connected),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        status?.batteryPercent?.let { bat ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = stringResource(R.string.dev_status_battery, bat),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = currentBaseUrl,
                        onValueChange = onUrlChanged,
                        label = { Text(stringResource(R.string.dev_label_device_address)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = currentBaseUrl == "http://crosspoint.local",
                            onClick = { onUrlChanged("http://crosspoint.local") },
                            label = { Text("crosspoint.local") }
                        )
                        FilterChip(
                            selected = currentBaseUrl == "http://192.168.4.1",
                            onClick = { onUrlChanged("http://192.168.4.1") },
                            label = { Text("192.168.4.1 (AP)") }
                        )
                    }

                    if (status != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (!status.deviceModel.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_detected_model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(status.deviceModel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.dev_label_wifi_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(status.wifiMode, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (status.firmwareVersion != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_firmware), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(status.firmwareVersion, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (status.freeHeap > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.dev_label_free_memory), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${status.freeHeap / 1024} KB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenDeviceWebUi,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.dev_btn_open_web_ui))
                    }
                }
            }
        }

        // Categorized Settings Cards
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else if (settingsList.isNotEmpty()) {
            val categorized = categorizeSettings(settingsList, isDe)

            categorized.forEach { (categoryTitle, items) ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = categoryTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        items.forEachIndexed { index, setting ->
                            SettingItemRow(
                                setting = setting,
                                isDe = isDe,
                                onUpdateSetting = onUpdateSetting,
                                onRequestPick = { s, opts -> settingToPick = s to opts },
                                onRequestEdit = { s ->
                                    settingToEdit = s
                                    editValueText = if (s.value.toString() == "null") "" else s.value.toString()
                                    showPasswordText = false
                                }
                            )
                            if (index < items.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // SD-Plugins Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dev_plugins_section_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (sdPlugins.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${sdPlugins.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onOpenPluginStore,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.dev_btn_store), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.dev_plugins_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (isLoadingPlugins) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(modifier = Modifier.size(24.dp))
                    }
                } else if (sdPlugins.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dev_no_plugins_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dev_no_plugins_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = onOpenPluginStore,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.dev_btn_plugin_store_github), style = MaterialTheme.typography.labelMedium)
                            }
                            FilledTonalButton(
                                onClick = onInitPluginsDir,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.dev_btn_create_folder), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                } else {
                    sdPlugins.forEachIndexed { idx, plugin ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (plugin.isEnabled) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Extension,
                                            contentDescription = null,
                                            tint = if (plugin.isEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = plugin.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "v${plugin.version}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = plugin.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Switch(
                                    checked = plugin.isEnabled,
                                    onCheckedChange = { onTogglePlugin(plugin, it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { onNavigateToExplorer("${plugin.parentDir.trimEnd('/')}/${plugin.folderName}") }
                                ) {
                                    Text(stringResource(R.string.dev_btn_files), style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                FilledTonalButton(
                                    onClick = { onConfigurePlugin(plugin) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(stringResource(R.string.dev_btn_configure), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (idx < sdPlugins.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // Vector Fonts Card (CrossPoint v2.0)
        if (isConnected && status?.vectorFonts == true) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_fonts_section_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "v2.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.dev_fonts_section_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onUploadFont,
                        enabled = !isUploadingFont,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUploadingFont) {
                            LoadingIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dev_btn_uploading_font))
                        } else {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dev_btn_upload_font))
                        }
                    }
                }
            }
        }
    }

    // Single-Choice Option Picker Dialog (Clean, accessible, zero popup bugs!)
    settingToPick?.let { (setting, options) ->
        val currentStrVal = setting.value.toString().trim()

        AlertDialog(
            onDismissRequest = { settingToPick = null },
            title = { Text(localizeSettingName(setting.name, isDe), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { opt ->
                        val isSelected = opt.value.equals(currentStrVal, ignoreCase = true) ||
                                opt.label.equals(currentStrVal, ignoreCase = true)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(
                                    selected = isSelected,
                                    onClick = {
                                        val outVal: Any = if (setting.value is Number) {
                                            opt.value.toIntOrNull() ?: opt.value
                                        } else {
                                            opt.value
                                        }
                                        onUpdateSetting(setting.key, outVal)
                                        settingToPick = null
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { settingToPick = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }

    // Text Input Dialog (for Username, Password, URL, Device Name)
    settingToEdit?.let { setting ->
        val isPassword = setting.key.contains("password", ignoreCase = true)

        AlertDialog(
            onDismissRequest = { settingToEdit = null },
            title = { Text(localizeSettingName(setting.name, isDe), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editValueText,
                        onValueChange = { editValueText = it },
                        label = { Text(localizeSettingName(setting.name, isDe)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isPassword && !showPasswordText) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = if (isPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                        trailingIcon = if (isPassword) {
                            {
                                IconButton(onClick = { showPasswordText = !showPasswordText }) {
                                    Icon(
                                        imageVector = if (showPasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            }
                        } else null
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateSetting(setting.key, editValueText.trim())
                        settingToEdit = null
                    }
                ) {
                    Text(stringResource(R.string.dev_btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { settingToEdit = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingItemRow(
    setting: CrossPointSettingItem,
    isDe: Boolean,
    onUpdateSetting: (String, Any) -> Unit,
    onRequestPick: (CrossPointSettingItem, List<SettingOption>) -> Unit,
    onRequestEdit: (CrossPointSettingItem) -> Unit
) {
    // 1. Boolean Switch (Night Mode, Focus Reading, Hyphenation, Hidden files, metadata, Style)
    if (isBooleanSetting(setting)) {
        val isChecked = parseBooleanValue(setting.value)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = localizeSettingName(setting.name, isDe),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Switch(
                checked = isChecked,
                onCheckedChange = { checked ->
                    val outVal: Any = when {
                        setting.options.size == 2 && setting.value is Number -> if (checked) 1 else 0
                        setting.value is Boolean -> checked
                        setting.value is Number || setting.value.toString() == "0" || setting.value.toString() == "1" -> if (checked) 1 else 0
                        else -> checked
                    }
                    onUpdateSetting(setting.key, outVal)
                }
            )
        }
        return
    }

    // 2. Option Picker (Theme, Font, Rotation, Sleep Timeout, KOReader Enums)
    val options = resolveSettingOptions(setting, isDe)
    if (options != null && options.isNotEmpty()) {
        val currentStr = setting.value.toString().trim()
        val matchingOpt = options.firstOrNull { it.value.equals(currentStr, ignoreCase = true) }
        val displayLabel = matchingOpt?.label ?: getSettingDisplayLabel(setting, isDe)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRequestPick(setting, options) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = localizeSettingName(setting.name, isDe),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        return
    }

    // 3. Number Slider (Screen Margin, Sleep Timeout, Brightness)
    val isNumber = setting.type == "value" || setting.type == "int" ||
            ((setting.value is Number || setting.value.toString().toIntOrNull() != null) && !isBooleanSetting(setting) && setting.options.isEmpty())

    if (isNumber) {
        val rawNum = (setting.value as? Number)?.toFloat() ?: setting.value.toString().toFloatOrNull() ?: 0f
        val minVal = setting.min?.toFloat() ?: 0f
        val maxVal = setting.max?.toFloat() ?: 60f
        val safeMax = if (maxVal > minVal) maxVal else minVal + 1f
        val stepVal = setting.step?.toFloat() ?: 1f
        val steps = if (stepVal > 0f) (((safeMax - minVal) / stepVal).toInt() - 1).coerceAtLeast(0) else 0
        val coercedVal = rawNum.coerceIn(minVal, safeMax)
        val sliderState = rememberSliderState(
            value = coercedVal,
            steps = steps,
            trackRange = minVal..safeMax
        )
        LaunchedEffect(coercedVal) {
            if (kotlin.math.abs(sliderState.value - coercedVal) > 0.001f) {
                sliderState.value = coercedVal
            }
        }

        val unit = when {
            setting.key.contains("margin", true) -> " px"
            setting.key.contains("timeout", true) || setting.key.contains("sleep", true) -> stringResource(R.string.dev_unit_minutes)
            setting.key.contains("size", true) -> " pt"
            else -> ""
        }

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(localizeSettingName(setting.name, isDe), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "${sliderState.value.toInt()}$unit",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                state = sliderState,
                onValueChange = { newVal ->
                    sliderState.value = newVal
                },
                onValueChangeFinished = {
                    val newVal = sliderState.value
                    val rounded = if (stepVal > 0f) {
                        (Math.round((newVal - minVal) / stepVal) * stepVal + minVal)
                    } else {
                        newVal
                    }
                    onUpdateSetting(setting.key, rounded.toInt())
                }
            )
        }
        return
    }

    // 4. Text Setting (Username, Password, Server URL)
    val isPassword = setting.key.contains("password", ignoreCase = true)
    val valStr = setting.value.toString().trim()
    val notSetLabel = stringResource(R.string.dev_value_not_set)
    val displayVal = when {
        isPassword && valStr.isNotBlank() && valStr != "null" -> "••••••••"
        valStr.isBlank() || valStr == "null" -> notSetLabel
        else -> valStr
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onRequestEdit(setting) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = localizeSettingName(setting.name, isDe),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayVal,
                style = MaterialTheme.typography.bodySmall,
                color = if (displayVal == notSetLabel) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun isBooleanSetting(setting: CrossPointSettingItem): Boolean {
    val type = setting.type.lowercase()
    if (type == "toggle" || type == "bool" || type == "boolean" || type == "checkbox" || type == "switch") return true
    if (setting.value is Boolean) return true

    // Check if options are ["Off", "On"] or ["Aus", "Ein"] or ["Disabled", "Enabled"]
    if (setting.options.size == 2) {
        val o0 = setting.options[0].lowercase()
        val o1 = setting.options[1].lowercase()
        if ((o0 == "off" && o1 == "on") || (o0 == "disabled" && o1 == "enabled") ||
            (o0 == "aus" && o1 == "ein") || (o0 == "aus" && o1 == "an")) {
            return true
        }
    }

    val strVal = setting.value.toString().trim().lowercase()
    if (strVal == "true" || strVal == "false") return true

    // Check key and name patterns for binary options (0/1 or empty/null)
    val key = setting.key.lowercase()
    val name = setting.name.lowercase()

    if (setting.options.isEmpty() && (strVal == "0" || strVal == "1" || strVal == "" || strVal == "null")) {
        if (name.startsWith("show") || name.startsWith("use") || name.startsWith("clear") ||
            name.startsWith("move") || name.startsWith("enable") || name.startsWith("disable") ||
            name.contains("hidden") || name.contains("battery") || name.contains("style") ||
            name.contains("daylight") || name.contains("header") ||
            key.contains("invert") || key.contains("battery") || key.contains("wifi_sleep") ||
            key.contains("clock_enable") || key.contains("daylight") || key.contains("header")
        ) {
            return true
        }
    }

    return false
}

private fun parseBooleanValue(value: Any): Boolean {
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() == 1
        else -> {
            val s = value.toString().trim().lowercase()
            s == "true" || s == "1"
        }
    }
}

private fun resolveSettingOptions(setting: CrossPointSettingItem, isDe: Boolean): List<SettingOption>? {
    val key = setting.key.lowercase()
    val name = setting.name.lowercase()

    // 1. Options provided by CrossPoint device JSON (UI Theme, Fonts, Refresh, Buttons, etc.)
    if (setting.options.isNotEmpty()) {
        return setting.options.mapIndexed { idx, opt ->
            val label = localizeOption(key, opt, isDe)
            val optVal = if (setting.value is Number || setting.value.toString().toIntOrNull() != null) {
                idx.toString()
            } else {
                opt
            }
            SettingOption(optVal, label)
        }
    }

    // 2. KOReader & Sync specific mappings when options array is empty
    if (key.contains("matching") || name.contains("document matching")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Dateiname (Filename)"),
                SettingOption("1", "Buchtitel (Title)"),
                SettingOption("2", "Vollständiger Pfad")
            )
        } else {
            listOf(
                SettingOption("0", "Filename"),
                SettingOption("1", "Book Title"),
                SettingOption("2", "Full Path")
            )
        }
    }
    if (key.contains("metadata") && (key.contains("send") || name.contains("send document metadata"))) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Deaktiviert"),
                SettingOption("1", "Nur Lesefortschritt"),
                SettingOption("2", "Vollständige Metadaten")
            )
        } else {
            listOf(
                SettingOption("0", "Disabled"),
                SettingOption("1", "Reading Progress Only"),
                SettingOption("2", "Full Metadata")
            )
        }
    }
    if (key.contains("sync") && (key.contains("behavior") || name.contains("sync behavior"))) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Nur Abrufen (Pull)"),
                SettingOption("1", "Nur Senden (Push)"),
                SettingOption("2", "Beidseitig (2-Wege-Sync)")
            )
        } else {
            listOf(
                SettingOption("0", "Pull Only"),
                SettingOption("1", "Push Only"),
                SettingOption("2", "Two-Way Sync")
            )
        }
    }

    // 3. Clock Format fallback
    if (key == "clock" || name == "clock" || key.contains("clock_format")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Aus"),
                SettingOption("1", "12-Stunden-Format"),
                SettingOption("2", "24-Stunden-Format")
            )
        } else {
            listOf(
                SettingOption("0", "Off"),
                SettingOption("1", "12-Hour Format"),
                SettingOption("2", "24-Hour Format")
            )
        }
    }

    // 4. Sleep Timeout fallback
    if (key.contains("sleep") && (key.contains("time") || key.contains("timeout")) || name.contains("time to sleep")) {
        return if (isDe) {
            listOf(
                SettingOption("5", "5 Minuten"),
                SettingOption("10", "10 Minuten"),
                SettingOption("15", "15 Minuten"),
                SettingOption("30", "30 Minuten"),
                SettingOption("0", "Nie (Aus)")
            )
        } else {
            listOf(
                SettingOption("5", "5 Minutes"),
                SettingOption("10", "10 Minutes"),
                SettingOption("15", "15 Minutes"),
                SettingOption("30", "30 Minutes"),
                SettingOption("0", "Never (Off)")
            )
        }
    }

    // 5. Rotation / Orientation fallback
    if (key.contains("rotation") || key.contains("orientation")) {
        return if (isDe) {
            listOf(
                SettingOption("0", "Hochformat (0°)"),
                SettingOption("1", "Querformat (90°)"),
                SettingOption("2", "Kopfstehend (180°)"),
                SettingOption("3", "Querformat (270°)")
            )
        } else {
            listOf(
                SettingOption("0", "Portrait (0°)"),
                SettingOption("1", "Landscape (90°)"),
                SettingOption("2", "Inverted Portrait (180°)"),
                SettingOption("3", "Inverted Landscape (270°)")
            )
        }
    }

    // 6. Timezone (Zeitzone)
    if (key.contains("timezone") || key.contains("tz") || key.contains("gmt") || name.contains("timezone") || name.contains("zeitzone")) {
        return if (isDe) {
            listOf(
                SettingOption("-12", "UTC-12:00 (Bakerinsel)"),
                SettingOption("-11", "UTC-11:00 (Samoa)"),
                SettingOption("-10", "UTC-10:00 (Hawaii)"),
                SettingOption("-9", "UTC-09:00 (Alaska)"),
                SettingOption("-8", "UTC-08:00 (Pazifik / Los Angeles)"),
                SettingOption("-7", "UTC-07:00 (Mountain / Denver)"),
                SettingOption("-6", "UTC-06:00 (Central / Chicago)"),
                SettingOption("-5", "UTC-05:00 (Eastern / New York)"),
                SettingOption("-4", "UTC-04:00 (Atlantik / Santiago)"),
                SettingOption("-3", "UTC-03:00 (Buenos Aires, São Paulo)"),
                SettingOption("-2", "UTC-02:00 (Mittelatlantik)"),
                SettingOption("-1", "UTC-01:00 (Azoren, Kap Verde)"),
                SettingOption("0", "UTC±00:00 (London, Dublin, Lissabon)"),
                SettingOption("1", "UTC+01:00 (Berlin, Paris, Rom, Wien)"),
                SettingOption("2", "UTC+02:00 (Kairo, Helsinki, Athen)"),
                SettingOption("3", "UTC+03:00 (Moskau, Istanbul, Riad)"),
                SettingOption("4", "UTC+04:00 (Dubai, Baku)"),
                SettingOption("5", "UTC+05:00 (Karatschi, Taschkent)"),
                SettingOption("6", "UTC+06:00 (Dhaka, Almaty)"),
                SettingOption("7", "UTC+07:00 (Bangkok, Jakarta)"),
                SettingOption("8", "UTC+08:00 (Singapur, Peking, Perth)"),
                SettingOption("9", "UTC+09:00 (Tokio, Seoul)"),
                SettingOption("10", "UTC+10:00 (Sydney, Melbourne)"),
                SettingOption("11", "UTC+11:00 (Nouméa, Salomonen)"),
                SettingOption("12", "UTC+12:00 (Auckland, Fidschi)"),
                SettingOption("13", "UTC+13:00 (Tonga)"),
                SettingOption("14", "UTC+14:00 (Kiritimati)")
            )
        } else {
            listOf(
                SettingOption("-12", "UTC-12:00 (Baker Island)"),
                SettingOption("-11", "UTC-11:00 (Samoa)"),
                SettingOption("-10", "UTC-10:00 (Hawaii)"),
                SettingOption("-9", "UTC-09:00 (Alaska)"),
                SettingOption("-8", "UTC-08:00 (Pacific / Los Angeles)"),
                SettingOption("-7", "UTC-07:00 (Mountain / Denver)"),
                SettingOption("-6", "UTC-06:00 (Central / Chicago)"),
                SettingOption("-5", "UTC-05:00 (Eastern / New York)"),
                SettingOption("-4", "UTC-04:00 (Atlantic / Santiago)"),
                SettingOption("-3", "UTC-03:00 (Buenos Aires, São Paulo)"),
                SettingOption("-2", "UTC-02:00 (Mid-Atlantic)"),
                SettingOption("-1", "UTC-01:00 (Azores, Cape Verde)"),
                SettingOption("0", "UTC±00:00 (London, Dublin, Lisbon)"),
                SettingOption("1", "UTC+01:00 (Berlin, Paris, Rome, Vienna)"),
                SettingOption("2", "UTC+02:00 (Cairo, Helsinki, Athens)"),
                SettingOption("3", "UTC+03:00 (Moscow, Istanbul, Riyadh)"),
                SettingOption("4", "UTC+04:00 (Dubai, Baku)"),
                SettingOption("5", "UTC+05:00 (Karachi, Tashkent)"),
                SettingOption("6", "UTC+06:00 (Dhaka, Almaty)"),
                SettingOption("7", "UTC+07:00 (Bangkok, Jakarta)"),
                SettingOption("8", "UTC+08:00 (Singapore, Beijing, Perth)"),
                SettingOption("9", "UTC+09:00 (Tokyo, Seoul)"),
                SettingOption("10", "UTC+10:00 (Sydney, Melbourne)"),
                SettingOption("11", "UTC+11:00 (Noumea, Solomon Islands)"),
                SettingOption("12", "UTC+12:00 (Auckland, Fiji)"),
                SettingOption("13", "UTC+13:00 (Tonga)"),
                SettingOption("14", "UTC+14:00 (Kiritimati)")
            )
        }
    }

    return null
}

private fun getSettingDisplayLabel(setting: CrossPointSettingItem, isDe: Boolean): String {
    val strVal = setting.value.toString().trim()
    val key = setting.key.lowercase()

    // 1. Resolve from device options list if present
    if (setting.options.isNotEmpty()) {
        val intIdx = strVal.toIntOrNull()
        if (intIdx != null && intIdx in setting.options.indices) {
            return localizeOption(key, setting.options[intIdx], isDe)
        }
        val match = setting.options.firstOrNull { it.equals(strVal, ignoreCase = true) }
        if (match != null) {
            return localizeOption(key, match, isDe)
        }
    }

    // 2. Known KOReader & System key translations
    return when {
        key.contains("matching") -> when (strVal) {
            "0" -> if (isDe) "Dateiname" else "File Name"
            "1" -> if (isDe) "Buchtitel" else "Book Title"
            "2" -> if (isDe) "Pfad" else "Path"
            else -> strVal
        }
        key.contains("metadata") -> when (strVal) {
            "0" -> if (isDe) "Deaktiviert" else "Disabled"
            "1" -> if (isDe) "Fortschritt" else "Progress"
            "2" -> if (isDe) "Vollständig" else "Complete"
            else -> strVal
        }
        key.contains("sync") && key.contains("behavior") -> when (strVal) {
            "0" -> if (isDe) "Nur Abrufen (Pull)" else "Pull only"
            "1" -> if (isDe) "Nur Senden (Push)" else "Push only"
            "2" -> if (isDe) "2-Wege" else "Two-way"
            else -> strVal
        }
        key == "clock" || key.contains("clock_format") -> when (strVal) {
            "0" -> if (isDe) "Aus" else "Off"
            "1" -> "12h"
            "2" -> "24h"
            else -> strVal
        }
        key.contains("sleep") && (key.contains("time") || key.contains("timeout")) -> when (strVal) {
            "0" -> if (isDe) "Nie" else "Never"
            else -> if (isDe) "$strVal Min." else "$strVal min."
        }
        else -> strVal.ifBlank { "-" }
    }
}

private fun localizeOption(key: String, rawOption: String, isDe: Boolean): String {
    if (!isDe) {
        return rawOption
    }
    val opt = rawOption.trim()
    return when (opt.lowercase()) {
        "classic" -> "Klassisch (Classic)"
        "lyra" -> "Lyra"
        "lyra extended" -> "Lyra Erweitert"
        "roundedraff" -> "Rounded Raff"
        "dark" -> "Dunkel (Schwarz)"
        "light" -> "Hell (Weiß)"
        "custom" -> "Eigenes Bild"
        "cover" -> "Aktuelles Buch-Cover"
        "cover + custom" -> "Cover, sonst Bild"
        "none" -> "Keiner"
        "quick resume" -> "Schnell-Fortsetzen"
        "transparent" -> "Transparent"
        "fit" -> "Einpassen (Fit)"
        "crop" -> "Zuschneiden (Crop)"
        "contrast" -> "Hoher Kontrast"
        "inverted" -> "Invertiert"
        "never" -> "Nie"
        "in reader" -> "Nur beim Lesen"
        "always" -> "Immer"
        "1 page" -> "Jede Seite"
        "5 pages" -> "Alle 5 Seiten"
        "10 pages" -> "Alle 10 Seiten"
        "15 pages" -> "Alle 15 Seiten"
        "30 pages" -> "Alle 30 Seiten"
        "noto serif" -> "Noto Serif (Standard)"
        "noto sans" -> "Noto Sans"
        "12 pt" -> "12 pt"
        "14 pt" -> "14 pt"
        "16 pt" -> "16 pt (Standard)"
        "18 pt" -> "18 pt"
        "tight" -> "Eng (Tight)"
        "normal" -> "Normal"
        "wide" -> "Weit (Wide)"
        "extra wide" -> "Sehr weit"
        "justify" -> "Blocksatz"
        "left" -> "Linksbündig"
        "center" -> "Zentriert"
        "right" -> "Rechtsbündig"
        "book's style" -> "Buch-Standard"
        "portrait" -> "Hochformat (0°)"
        "landscape cw" -> "Querformat (90°)"
        "portrait 180°" -> "Kopfstehend (180°)"
        "landscape ccw" -> "Querformat (270°)"
        "display" -> "Bilder anzeigen"
        "placeholder" -> "Platzhalter anzeigen"
        "suppress" -> "Bilder ausblenden"
        "list" -> "Listenansicht"
        "toolbar" -> "Symbolleiste"
        "prev/next" -> "Oben: Zurück / Unten: Vor"
        "next/prev" -> "Oben: Vor / Unten: Zurück"
        "disabled" -> "Deaktiviert"
        "off" -> "Aus"
        "on" -> "Ein"
        "tap" -> "Tippen (Tap)"
        "swipe" -> "Wischen (Swipe)"
        "inverted tap" -> "Invertiertes Tippen"
        "chapter skip" -> "Kapitel überspringen"
        "orientation change" -> "Ausrichtung drehen"
        "kosync" -> "KOSync ausführen"
        "bookmark" -> "Lesezeichen"
        "dictionary" -> "Wörterbuch"
        "ignore" -> "Ignorieren"
        "sleep" -> "Standby"
        "page turn" -> "Seite umblättern"
        "refresh screen" -> "Bildschirm auffrischen"
        "footnotes" -> "Fußnoten"
        "confirm" -> "Bestätigen"
        else -> rawOption
    }
}

private fun localizeSettingName(rawName: String, isDe: Boolean): String {
    if (!isDe) {
        return rawName
    }
    return when (rawName) {
        "Sleep Screen" -> "Ruhebildschirm"
        "Sleep Screen Cover Mode" -> "Cover-Skalierung im Standby"
        "Sleep Screen Cover Filter" -> "Cover-Filter im Standby"
        "Quick Resume on Timeout" -> "Schnell-Fortsetzen bei Timeout"
        "Hide Battery %" -> "Akkustand in %"
        "Refresh Frequency" -> "Bildschirm-Auffrischungsrate"
        "UI Theme" -> "Design-Theme"
        "Night Mode" -> "Nachtmodus (Invertieren)"
        "Reader Font Family" -> "Schriftart im Reader"
        "Reader Font Size" -> "Schriftgröße"
        "Line Spacing" -> "Zeilenabstand"
        "Screen Margin" -> "Seitenrand"
        "Paragraph Alignment" -> "Textausrichtung"
        "Embedded Style" -> "Buch-eigenes Stylesheet nutzen"
        "Focus Reading" -> "Fokus-Lesemodus (Bionic)"
        "Hyphenation" -> "Silbentrennung"
        "Reading Orientation" -> "Leseausrichtung"
        "Extra Paragraph Spacing" -> "Zusätzlicher Absatzabstand"
        "Text Anti-Aliasing" -> "Text-Kantenglättung"
        "Images" -> "Bilder in Büchern"
        "Reader Menu Style" -> "Menüstil im Reader"
        "Side Button Layout (Reader)" -> "Seitentasten-Belegung"
        "Touch Reader Controls" -> "Touch-Gesten beim Lesen"
        "Long-Press Button Behavior" -> "Langer Tastendruck"
        "Long-Press Menu" -> "Langer Druck Menüfunktion"
        "Short Power Button Click" -> "Kurzer Power-Tastendruck"
        "Tilt Page Turn" -> "Umblättern durch Neigen"
        "Quick-Return from Footnotes" -> "Schnell-Rückkehr aus Fußnoten"
        "Time to Sleep" -> "Automatischer Ruhezustand nach"
        "Show Hidden Files" -> "Versteckte Dateien anzeigen"
        "Use Book Metadata" -> "Buch-Metadaten nutzen"
        "Clear Read Books from Recent List" -> "Gelesene Bücher aus Verlauf entfernen"
        "Move Finished Books to Read Folder" -> "Gelesene Bücher in 'read'-Ordner verschieben"
        "Download Folder" -> "Download-Verzeichnis"
        "Send Document Metadata" -> "Dokument-Metadaten an Sync senden"
        "Document Matching" -> "Dokumenten-Abgleich"
        "Sync Behavior" -> "Synchronisations-Richtung"
        "Clock" -> "Uhranzeige"
        "Clock Format" -> "Uhrzeit-Format"
        "Time Zone" -> "Zeitzone"
        "Daylight Saving" -> "Sommerzeit (DST)"
        "Show in Header" -> "In Kopfzeile anzeigen"
        "Battery" -> "Batterie-Anzeige"
        "KOReader Username" -> "KOReader Benutzername"
        "KOReader Password" -> "KOReader Passwort"
        "Sync Server URL" -> "KOReader Server URL"
        "Bluetooth Page Turner" -> "Bluetooth-Blätterfernbedienung"
        "Kerning" -> "Kerning (Buchstabenabstand)"
        "Dictionary" -> "Wörterbuch (StarDict)"
        "Highlights & Clippings" -> "Markierungen & Notizen"
        "Sync Clippings" -> "Markierungen synchronisieren"
        "Sync Bookmarks" -> "Lesezeichen synchronisieren"
        else -> rawName
    }
}

private fun categorizeSettings(list: List<CrossPointSettingItem>, isDe: Boolean): Map<String, List<CrossPointSettingItem>> {
    val cloudItems = mutableListOf<CrossPointSettingItem>()
    val readerItems = mutableListOf<CrossPointSettingItem>()
    val displayItems = mutableListOf<CrossPointSettingItem>()
    val controlItems = mutableListOf<CrossPointSettingItem>()
    val systemItems = mutableListOf<CrossPointSettingItem>()
    val otherItems = mutableListOf<CrossPointSettingItem>()

    for (item in list) {
        val cat = item.category.trim().lowercase()
        val k = item.key.lowercase()
        val n = item.name.lowercase()

        when {
            cat == "cloud" || k.contains("koreader") || k.contains("sync") || n.contains("koreader") || n.contains("matching") -> {
                cloudItems.add(item)
            }
            cat == "reader" || k.contains("font") || k.contains("spacing") || k.contains("margin") || k.contains("alignment") || k.contains("reading") || k.contains("hyphenation") ||
                k.contains("kerning") || k.contains("dictionary") || k.contains("stardict") || k.contains("clipping") || k.contains("highlight") || k.contains("bookmark") || k.contains("footnote") || k.contains("ruby") -> {
                readerItems.add(item)
            }
            cat == "display" || k.contains("theme") || k.contains("screen") || k.contains("refresh") || k.contains("invert") -> {
                displayItems.add(item)
            }
            cat == "controls" || k.contains("button") || k.contains("touch") || k.contains("tilt") || k.contains("press") ||
                k.contains("bluetooth") || k.contains("bt_") || k.contains("turner") || k.contains("remote") || k.contains("gesture") -> {
                controlItems.add(item)
            }
            cat == "system" || k.contains("sleep") || k.contains("timeout") || k.contains("battery") || k.contains("clock") || k.contains("timezone") || k.contains("hidden") || k.contains("recent") || k.contains("readfolder") || k.contains("finished") -> {
                systemItems.add(item)
            }
            else -> otherItems.add(item)
        }
    }

    val map = linkedMapOf<String, List<CrossPointSettingItem>>()
    if (cloudItems.isNotEmpty()) map[if (isDe) "KOReader & Cloud-Sync" else "KOReader & Cloud Sync"] = cloudItems
    if (readerItems.isNotEmpty()) map[if (isDe) "Leseerlebnis & Typografie" else "Reading Experience & Typography"] = readerItems
    if (displayItems.isNotEmpty()) map[if (isDe) "Anzeige & Bildschirmschoner" else "Display & Screensaver"] = displayItems
    if (controlItems.isNotEmpty()) map[if (isDe) "Tasten & Gestensteuerung" else "Buttons & Gesture Controls"] = controlItems
    if (systemItems.isNotEmpty()) map[if (isDe) "System & Energie" else "System & Power"] = systemItems
    if (otherItems.isNotEmpty()) map[if (isDe) "Weitere Optionen" else "Other Options"] = otherItems

    return if (map.isNotEmpty()) map else list.groupBy { it.category }
}

private fun saveToPublicDownloads(context: Context, fileName: String, file: File): Boolean {
    return try {
        val safeName = File(fileName).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "download.bin" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, when {
                    safeName.endsWith(".epub", true) -> "application/epub+zip"
                    safeName.endsWith(".pdf", true) -> "application/pdf"
                    safeName.endsWith(".bmp", true) -> "image/bmp"
                    safeName.endsWith(".png", true) -> "image/png"
                    safeName.endsWith(".jpg", true) || safeName.endsWith(".jpeg", true) -> "image/jpeg"
                    safeName.endsWith(".txt", true) -> "text/plain"
                    else -> "application/octet-stream"
                })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CrossPoint")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                true
            } else false
        } else {
            val baseDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val targetDir = File(baseDownloads, "CrossPoint").apply { mkdirs() }
            val dest = ensureInsideDirectory(targetDir, File(targetDir, safeName))
            file.copyTo(dest, overwrite = true)
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun isSafeSdCardPath(path: String): Boolean {
    if (path.isBlank()) return false
    val normalized = path.replace('\\', '/')
    if (!normalized.startsWith("/")) return false
    val segments = normalized.split('/')
    if (segments.any { it == ".." }) return false
    return true
}

private fun ensureInsideDirectory(dir: File, file: File): File {
    val dirCanonical = dir.canonicalFile
    val fileCanonical = file.canonicalFile
    if (!fileCanonical.path.startsWith(dirCanonical.path + File.separator)) {
        throw SecurityException("Invalid file path: $file outside of $dir")
    }
    return file
}

internal val PROTECTED_CROSSPOINT_CONFIG_FILES = setOf(
    "/.crosspoint/settings.json",
    "/.crosspoint/wifi.json",
    "/.crosspoint/opds.json",
    "/.crosspoint/koreader.json"
)

internal val RESERVED_PLUGIN_NAMES = setOf(
    "settings",
    "wifi",
    "opds",
    "koreader",
    "plugins",
    "cache"
)

internal fun isAllowedPluginConfigPath(folderName: String, parentDir: String, candidatePath: String): Boolean {
    val cleanFolder = folderName.trim().trim('/', '\\')
    if (cleanFolder.isEmpty() || cleanFolder == "." || cleanFolder == ".." || cleanFolder.lowercase() in RESERVED_PLUGIN_NAMES) {
        return false
    }
    val normalized = com.crosssend.app.network.WebDavClient.sanitizeRemotePath(candidatePath)
    if (normalized == "/" || normalized.lowercase() in PROTECTED_CROSSPOINT_CONFIG_FILES) return false

    val parentPrefix = "${com.crosssend.app.network.WebDavClient.sanitizeRemotePath(parentDir).trimEnd('/')}/$cleanFolder/"
    val pluginsPrefix = "/plugins/$cleanFolder/"
    val dotCrosspointPluginsPrefix = "/.crosspoint/plugins/$cleanFolder/"
    if (normalized.startsWith(parentPrefix) ||
        normalized.startsWith(pluginsPrefix) ||
        normalized.startsWith(dotCrosspointPluginsPrefix)
    ) {
        return true
    }

    val hyphenVariant = cleanFolder.replace('_', '-')
    val underscoreVariant = cleanFolder.replace('-', '_')
    val allowedRootConfigs = buildSet {
        add("/.crosspoint/$cleanFolder.json".lowercase())
        add("/.crosspoint/$hyphenVariant.json".lowercase())
        add("/.crosspoint/$underscoreVariant.json".lowercase())
        if (cleanFolder.equals("monthwallpaper", ignoreCase = true)) {
            add("/.crosspoint/month-wallpaper.json")
        }
    }
    return normalized.lowercase() in allowedRootConfigs
}

private fun isAllowedLocalAddress(addr: java.net.InetAddress): Boolean {
    return !addr.isLoopbackAddress &&
        !addr.isAnyLocalAddress &&
        !addr.isMulticastAddress &&
        (addr.isSiteLocalAddress || addr.isLinkLocalAddress)
}

private fun isLocalDeviceHost(host: String, apNet: android.net.Network?): Boolean {
    val cleanHost = host.lowercase().trim().removeSurrounding("[", "]")
    if (cleanHost.isBlank() || cleanHost == "localhost" || cleanHost == "0.0.0.0" ||
        cleanHost == "::" || cleanHost == "::1" || cleanHost == "0:0:0:0:0:0:0:1" ||
        cleanHost.startsWith("127.") || cleanHost.startsWith("::ffff:127.")
    ) {
        return false
    }
    if (!EInkImageOptimizer.isSafeExternalImageUrl("http://$cleanHost")) {
        return true
    }
    return try {
        val addrs = if (apNet != null) {
            apNet.getAllByName(cleanHost)
        } else {
            java.net.InetAddress.getAllByName(cleanHost)
        }
        addrs.isNotEmpty() && addrs.all { isAllowedLocalAddress(it) }
    } catch (_: Exception) {
        false
    }
}

private class ScopedPluginJsBridge(
    private val context: Context,
    private val resources: android.content.res.Resources,
    private val app: CrossSendApp,
    private val plugin: SdPluginInfo,
    private val deviceBaseUrl: String,
    private val allowCryptoOverride: () -> Boolean = { false }
) {
    @Volatile
    private var cachedDeviceNetwork: android.net.Network? = null
    @Volatile
    private var cachedDeviceHost: String? = null
    @Volatile
    private var cachedDeviceClient: okhttp3.OkHttpClient? = null
    private val clientLock = Any()

    private fun resolveDeviceHttpUrlIfAllowed(url: String): okhttp3.OkHttpClient? {
        val parsed = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http") return null
        val deviceHost = try {
            java.net.URI(deviceBaseUrl.trim()).host?.lowercase()?.removeSurrounding("[", "]")
        } catch (_: Exception) {
            null
        } ?: return null
        val reqHost = parsed.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
        if (reqHost != deviceHost) return null

        val apNet = app.wifiManager.getApNetwork()
        if (!isLocalDeviceHost(deviceHost, apNet)) return null

        synchronized(clientLock) {
            val existing = cachedDeviceClient
            if (existing != null && cachedDeviceNetwork == apNet && cachedDeviceHost == deviceHost) {
                return existing
            }
            val builder = okhttp3.OkHttpClient.Builder()
            if (apNet != null) {
                builder.socketFactory(apNet.socketFactory)
            }
            builder.dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val clean = hostname.lowercase().trim().removeSurrounding("[", "]")
                    if (clean != deviceHost) {
                        throw java.net.UnknownHostException("Blocked non-device host: $hostname")
                    }
                    val addrs = if (apNet != null) {
                        try {
                            apNet.getAllByName(hostname).toList()
                        } catch (_: Exception) {
                            java.net.InetAddress.getAllByName(hostname).toList()
                        }
                    } else {
                        java.net.InetAddress.getAllByName(hostname).toList()
                    }
                    val localAddrs = addrs.filter { isAllowedLocalAddress(it) }
                    if (localAddrs.isEmpty()) {
                        throw java.net.UnknownHostException("Host is not a local device address: $hostname")
                    }
                    return localAddrs
                }
            })
                .addInterceptor { chain ->
                    val req = chain.request()
                    val reqUri = req.url
                    val reqUriHost = reqUri.host.lowercase().removeSurrounding("[", "]")
                    if (reqUriHost != deviceHost || (reqUri.scheme != "http" && reqUri.scheme != "https") || !isLocalDeviceHost(reqUriHost, apNet)) {
                        throw java.io.IOException("Unsafe device redirect")
                    }
                    val resp = chain.proceed(req)
                    val finalUrl = resp.request.url
                    val finalHost = finalUrl.host.lowercase().removeSurrounding("[", "]")
                    if (finalHost != deviceHost || (finalUrl.scheme != "http" && finalUrl.scheme != "https") || !isLocalDeviceHost(finalHost, apNet)) {
                        resp.close()
                        throw java.io.IOException("Unsafe device redirect")
                    }
                    if (resp.isRedirect) {
                        val location = resp.header("Location")
                        if (location != null) {
                            val resolved = resp.request.url.resolve(location)
                            val resolvedHost = resolved?.host?.lowercase()?.removeSurrounding("[", "]")
                            if (resolved == null || resolvedHost != deviceHost || (resolved.scheme != "http" && resolved.scheme != "https") || !isLocalDeviceHost(resolvedHost, apNet)) {
                                resp.close()
                                throw java.io.IOException("Unsafe device redirect Location")
                            }
                        }
                    }
                    resp
                }
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)

            return builder.build().also {
                cachedDeviceNetwork = apNet
                cachedDeviceHost = deviceHost
                cachedDeviceClient = it
            }
        }
    }

    private fun buildHttpsInternetClient(): okhttp3.OkHttpClient {
        return app.wifiManager.getInternetOkHttpClientBuilder()
            .addInterceptor { chain ->
                val reqUrl = chain.request().url.toString()
                if (!isSafeHttpsExternalUrl(reqUrl)) {
                    throw java.io.IOException("Unsafe or non-HTTPS URL")
                }
                val resp = chain.proceed(chain.request())
                if (!isSafeHttpsExternalUrl(resp.request.url.toString())) {
                    resp.close()
                    throw java.io.IOException("Unsafe or non-HTTPS redirect")
                }
                resp
            }
            .build()
    }
    private fun resolveScopedPath(path: String): String {
        val clean = path.trim()
        if (clean.isEmpty()) throw SecurityException("Empty path not allowed")
        val segments = clean.split("/", "\\").filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw SecurityException("Path traversal blocked: $path")
        }

        val cleanFolder = plugin.folderName.trim().trim('/', '\\')
        if (cleanFolder.isEmpty() || cleanFolder == "." || cleanFolder == ".." || cleanFolder.lowercase() in RESERVED_PLUGIN_NAMES) {
            throw SecurityException("Reserved plugin folder name")
        }

        val pluginDirPrefix = "${plugin.parentDir.trimEnd('/')}/$cleanFolder/"
        val altPluginDirPrefix = "/plugins/$cleanFolder/"
        val dotCrosspointPluginDirPrefix = "/.crosspoint/plugins/$cleanFolder/"

        val allowedDotCrosspointJson = "/.crosspoint/$cleanFolder.json"

        if (!clean.startsWith("/")) {
            val relClean = segments.joinToString("/")
            val resolved = "${plugin.parentDir.trimEnd('/')}/$cleanFolder/$relClean"
            if (resolved.lowercase() in PROTECTED_CROSSPOINT_CONFIG_FILES) {
                throw SecurityException("Access to protected CrossPoint system file denied")
            }
            if (resolved.startsWith(pluginDirPrefix)) {
                return resolved
            }
            throw SecurityException("Access denied: path outside plugin directory ($resolved)")
        }

        val normalized = "/" + segments.joinToString("/")

        if (normalized.lowercase() in PROTECTED_CROSSPOINT_CONFIG_FILES) {
            throw SecurityException("Access to protected CrossPoint system file denied")
        }

        if (normalized.startsWith(pluginDirPrefix) ||
            normalized.startsWith(altPluginDirPrefix) ||
            normalized.startsWith(dotCrosspointPluginDirPrefix)) {
            return normalized
        }

        if (normalized == allowedDotCrosspointJson ||
            isAllowedPluginConfigPath(cleanFolder, plugin.parentDir, normalized)
        ) {
            return normalized
        }

        throw SecurityException("Access denied: path outside plugin directory ($normalized)")
    }

    @JavascriptInterface
    fun readFile(path: String): String {
        return try {
            val normalized = resolveScopedPath(path)
            runBlocking {
                app.webDavClient.readTextFile(normalized) ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun writeFileInternal(path: String, data: String, isBase64: Boolean): Boolean {
        return try {
            if (data.length > 7 * 1024 * 1024) return false
            val normalized = resolveScopedPath(path)
            val content = if (isBase64) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                if (bytes.size > 5 * 1024 * 1024) return false
                String(bytes, Charsets.UTF_8)
            } else {
                if (data.toByteArray(Charsets.UTF_8).size > 5 * 1024 * 1024) return false
                data
            }
            val success = runBlocking {
                app.webDavClient.writeTextFile(normalized, content)
            }
            if (success) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, resources.getString(R.string.dev_settings_saved), Toast.LENGTH_SHORT).show()
                }
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun writeFile(path: String, rawData: String): Boolean {
        return writeFileInternal(path, rawData, isBase64 = false)
    }

    @JavascriptInterface
    fun writeFileEncoded(path: String, data: String, isBase64: Boolean): Boolean {
        return writeFileInternal(path, data, isBase64)
    }

    @JavascriptInterface
    fun writeFileWithEncoding(path: String, data: String, isBase64: Boolean): Boolean {
        return writeFileInternal(path, data, isBase64)
    }

    @JavascriptInterface
    fun relay(method: String, url: String, headersJson: String, body: String): String {
        val deviceLanClient = resolveDeviceHttpUrlIfAllowed(url)
        if (deviceLanClient == null && !isSafeHttpsExternalUrl(url)) {
            return JSONObject().put("status", 403).put("error", "SSRF or non-HTTPS URL blocked").toString()
        }
        return try {
            val client = deviceLanClient ?: buildHttpsInternetClient()
            val reqBuilder = okhttp3.Request.Builder().url(url)
            if (headersJson.isNotBlank()) {
                try {
                    val headersObj = JSONObject(headersJson)
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        reqBuilder.addHeader(key, headersObj.getString(key))
                    }
                } catch (_: Exception) {}
            }
            val upperMethod = method.trim().uppercase().ifBlank { "GET" }
            val reqBody = if (upperMethod in listOf("POST", "PUT", "PATCH")) {
                val mediaType = (try {
                    if (headersJson.isNotBlank()) {
                        JSONObject(headersJson).optString("Content-Type").takeIf { it.isNotBlank() }
                    } else null
                } catch (_: Exception) { null })?.toMediaType() ?: "application/json; charset=utf-8".toMediaType()
                body.toRequestBody(mediaType)
            } else null

            reqBuilder.method(upperMethod, reqBody)
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val bytes = resp.body?.source()?.let { src ->
                    src.request(10L * 1024 * 1024 + 1)
                    if (src.buffer.size > 10L * 1024 * 1024) throw java.io.IOException("Response exceeds 10 MB limit")
                    src.buffer.readByteArray()
                } ?: ByteArray(0)
                val respBody = String(bytes, Charsets.UTF_8)
                JSONObject().apply {
                    put("status", resp.code)
                    put("body", respBody)
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", 500)
                put("error", e.message ?: "Request failed")
            }.toString()
        }
    }

    @JavascriptInterface
    fun fetchToSd(url: String, dest: String, headersJson: String): String {
        val normalizedDest = try {
            resolveScopedPath(dest)
        } catch (e: Exception) {
            return JSONObject().put("status", 403).put("error", "Security violation: ${e.message}").toString()
        }
        val deviceLanClient = resolveDeviceHttpUrlIfAllowed(url)
        if (deviceLanClient == null && !isSafeHttpsExternalUrl(url)) {
            return JSONObject().put("status", 403).put("error", "Security violation: HTTPS required or invalid URL").toString()
        }
        return try {
            val client = deviceLanClient ?: buildHttpsInternetClient()
            val reqBuilder = okhttp3.Request.Builder().url(url).get()
            if (headersJson.isNotBlank()) {
                try {
                    val headersObj = JSONObject(headersJson)
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        reqBuilder.addHeader(key, headersObj.getString(key))
                    }
                } catch (_: Exception) {}
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return JSONObject().put("status", resp.code).put("error", "HTTP ${resp.code}").toString()
                }
                val bytes = resp.body?.source()?.let { src ->
                    src.request(10L * 1024 * 1024 + 1)
                    if (src.buffer.size > 10L * 1024 * 1024) throw java.io.IOException("Response exceeds 10 MB limit")
                    src.buffer.readByteArray()
                } ?: ByteArray(0)
                val success = runBlocking {
                    app.webDavClient.uploadBytes(normalizedDest, bytes)
                }
                if (success) {
                    JSONObject().put("status", 200).put("success", true).toString()
                } else {
                    JSONObject().put("status", 500).put("error", "Write to SD failed").toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().put("status", 500).put("error", e.message ?: "Download failed").toString()
        }
    }

    private fun isCryptoPermitted(): Boolean {
        if (plugin.hasDeclaredPermissions) {
            return "crypto" in plugin.permissions
        }
        return plugin.folderName.equals("protected-content", ignoreCase = true) || allowCryptoOverride()
    }

    @JavascriptInterface
    fun crypto(requestJson: String): String {
        if (!isCryptoPermitted()) {
            return JSONObject()
                .put("status", 403)
                .put("error", "Crypto capability denied for plugin ${plugin.folderName}")
                .toString()
        }
        return try {
            val res = runBlocking(Dispatchers.IO) {
                app.deviceClient.executeCrypto(deviceBaseUrl, requestJson)
            }
            res.getOrElse { err ->
                JSONObject().put("error", err.message ?: "Crypto failed").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Crypto failed").toString()
        }
    }

    @JavascriptInterface
    fun enqueueJob(pluginName: String, action: String, argsJson: String): String {
        return try {
            val requestedPlugin = pluginName.trim()
            if (requestedPlugin.isNotEmpty() && requestedPlugin != plugin.folderName) {
                return JSONObject()
                    .put("status", 403)
                    .put("error", "Cross-plugin job enqueue denied")
                    .toString()
            }
            val targetPlugin = plugin.folderName
            val trimmedArgs = argsJson.trim()
            val parsedArgs = if (trimmedArgs.isNotBlank() && trimmedArgs != "null" && trimmedArgs != "undefined") {
                JSONObject(trimmedArgs)
            } else {
                null
            }
            val res = runBlocking(Dispatchers.IO) {
                app.deviceClient.enqueuePluginJob(deviceBaseUrl, targetPlugin, action, parsedArgs)
            }
            res.getOrElse { err ->
                JSONObject().put("error", err.message ?: "Enqueue job failed").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Enqueue job failed").toString()
        }
    }
}

private fun buildPluginHostHtml(
    folderName: String,
    pluginJs: String,
    isDarkTheme: Boolean,
    deviceStatus: CrossPointStatus? = null
): String {
    val bgColor = if (isDarkTheme) "#141218" else "#fef7ff"
    val surfaceColor = if (isDarkTheme) "#1d1b20" else "#ffffff"
    val surfaceContainer = if (isDarkTheme) "#2b2930" else "#f3edf7"
    val primary = if (isDarkTheme) "#d0bcff" else "#6750a4"
    val onPrimary = if (isDarkTheme) "#381e72" else "#ffffff"
    val primaryContainer = if (isDarkTheme) "#4f378b" else "#eaddff"
    val onPrimaryContainer = if (isDarkTheme) "#eaddff" else "#21005d"
    val textColor = if (isDarkTheme) "#e6e0e9" else "#1d1b20"
    val textVariant = if (isDarkTheme) "#cac4d0" else "#49454f"
    val outlineVariant = if (isDarkTheme) "#49454f" else "#cac4d0"

    val safePluginJs = pluginJs
        .replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script")
        .replace(Regex("</style", RegexOption.IGNORE_CASE), "<\\/style")
    val quotedFolderName = JSONObject.quote(folderName).replace("</", "<\\/")
    val startErrPrefix = try {
        CrossSendApp.instance.getString(R.string.dev_plugin_start_error)
    } catch (_: Throwable) {
        "Error starting plugin: "
    }
    val quotedStartErrPrefix = JSONObject.quote(startErrPrefix).replace("</", "<\\/")
    val capabilitiesJson = JSONArray(deviceStatus?.pluginCapabilities ?: emptyList<String>()).toString()
    val pluginApiStr = deviceStatus?.pluginApi?.toString() ?: "null"
    val vectorFontsBool = deviceStatus?.vectorFonts ?: false

    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
<style>
  :root {
    --bg-color: $bgColor;
    --surface-color: $surfaceColor;
    --surface-container: $surfaceContainer;
    --primary: $primary;
    --on-primary: $onPrimary;
    --primary-container: $primaryContainer;
    --on-primary-container: $onPrimaryContainer;
    --text: $textColor;
    --text-variant: $textVariant;
    --outline-variant: $outlineVariant;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: var(--bg-color);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 15px;
    line-height: 1.5;
    padding: 16px;
    padding-bottom: 80px;
    -webkit-tap-highlight-color: transparent;
  }
  h1, h2, h3, h4 { font-weight: 700; margin-bottom: 10px; color: var(--text); }
  p { margin-bottom: 10px; color: var(--text-variant); font-size: 14px; }
  .card {
    background: var(--surface-color);
    border-radius: 20px;
    padding: 20px;
    margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  }
  .form-group, .setting-row {
    margin: 14px 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  label, .setting-name {
    display: block;
    font-size: 13px;
    font-weight: 600;
    color: var(--text-variant);
  }
  .setting-control { width: 100%; }
  input[type="text"], input[type="password"], input[type="url"], input[type="number"], input[type="email"], select, textarea {
    width: 100%;
    padding: 12px 16px;
    border: 1px solid var(--outline-variant);
    border-radius: 12px;
    background: var(--surface-container);
    color: var(--text);
    font-size: 15px;
    outline: none;
    transition: border-color 0.2s, box-shadow 0.2s;
  }
  input:focus, select:focus, textarea:focus {
    border-color: var(--primary);
    box-shadow: 0 0 0 1px var(--primary);
  }
  button, .btn, .btn-small, input[type="submit"], input[type="button"] {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    border-radius: 24px;
    padding: 10px 20px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    border: 1px solid var(--outline-variant);
    background: var(--surface-container);
    color: var(--text);
    margin-right: 6px;
    margin-top: 6px;
    transition: opacity 0.15s, transform 0.1s;
  }
  button.btn-add, .btn-add, button[type="submit"], input[type="submit"] {
    background: var(--primary);
    color: var(--on-primary);
    border: none;
  }
  button:active, .btn:active {
    opacity: 0.85;
    transform: scale(0.98);
  }
  .status-badge {
    display: inline-block;
    padding: 4px 10px;
    border-radius: 8px;
    background: var(--primary-container);
    color: var(--on-primary-container);
    font-size: 12px;
    font-weight: 600;
    margin-bottom: 12px;
  }
  #plugin-container { width: 100%; }
</style>
<script>
  window.CrossPoint = {
    name: $quotedFolderName,
    folderName: $quotedFolderName,
    deviceCapabilities: {
      pluginApi: $pluginApiStr,
      capabilities: $capabilitiesJson,
      vectorFonts: $vectorFontsBool
    },
    readFile: async function(path) {
      if (!window.AndroidBridge) return null;
      var text = window.AndroidBridge.readFile(path);
      return text || null;
    },
    writeFile: async function(path, data) {
      if (!window.AndroidBridge) return false;
      var text = (data === null || data === undefined) ? "" : String(data);
      var b64 = window.btoa(unescape(encodeURIComponent(text)));
      if (window.AndroidBridge.writeFileEncoded) {
        return window.AndroidBridge.writeFileEncoded(path, b64, true);
      }
      return window.AndroidBridge.writeFile(path, text);
    },
    relay: async function(methodOrOpts, url, headers, body) {
      if (!window.AndroidBridge) return { status: 500, error: "Bridge not available" };
      var m = "GET", u = "", h = {}, b = "";
      if (typeof methodOrOpts === "object" && methodOrOpts !== null) {
        m = (methodOrOpts.method || "GET").toUpperCase();
        u = methodOrOpts.url || "";
        h = methodOrOpts.headers || {};
        b = typeof methodOrOpts.body === "string" ? methodOrOpts.body : JSON.stringify(methodOrOpts.body || "");
      } else {
        m = (methodOrOpts || "GET").toUpperCase();
        u = url || "";
        h = headers || {};
        b = typeof body === "string" ? body : (body ? JSON.stringify(body) : "");
      }
      var res = window.AndroidBridge.relay(m, u, JSON.stringify(h), b);
      try { return JSON.parse(res); } catch(e) { return { status: 500, error: e.message }; }
    },
    fetchToSd: async function(url, dest, headers) {
      if (!window.AndroidBridge) return { status: 500, error: "Bridge not available" };
      var res = window.AndroidBridge.fetchToSd(url, dest, JSON.stringify(headers || {}));
      try { return JSON.parse(res); } catch(e) { return { status: 500, error: e.message }; }
    },
    crypto: async function(req) {
      if (!window.AndroidBridge || !window.AndroidBridge.crypto) {
        return { error: "Crypto bridge not available" };
      }
      var payload = typeof req === "string" ? req : JSON.stringify(req || {});
      var res = window.AndroidBridge.crypto(payload);
      try { return JSON.parse(res); } catch(e) { return { error: e.message || "Invalid JSON response" }; }
    },
    enqueueJob: async function(plugin, action, args) {
      if (!window.AndroidBridge || !window.AndroidBridge.enqueueJob) {
        return { error: "Job bridge not available" };
      }
      var argsStr = typeof args === "string" ? args : (args ? JSON.stringify(args) : "");
      var res = window.AndroidBridge.enqueueJob(plugin || this.name, action || "", argsStr);
      try { return JSON.parse(res); } catch(e) { return { error: e.message || "Invalid JSON response" }; }
    },
    registerAction: function(name, fn) {
      window._pluginActions = window._pluginActions || {};
      window._pluginActions[name] = fn;
    },
    registerPlugin: function(fn) {
      window._pluginInitFn = fn;
    }
  };
  window.registerPlugin = window.CrossPoint.registerPlugin;
</script>
</head>
<body>
  <div id="plugin-container"></div>
  <script>
    $safePluginJs

    (async function() {
      var container = document.getElementById("plugin-container");
      var plugin = window._pluginInitFn;
      if (plugin && container) {
        try {
          if (typeof plugin === "function") {
            await plugin(container, window.CrossPoint);
          } else if (typeof plugin.renderSettings === "function") {
            await plugin.renderSettings(container, window.CrossPoint);
          } else if (typeof plugin.render === "function") {
            await plugin.render(container, window.CrossPoint);
          } else if (typeof plugin.init === "function") {
            await plugin.init(container, window.CrossPoint);
          }
        } catch (e) {
          container.innerHTML = '';
          var errDiv = document.createElement('div');
          errDiv.className = 'card';
          errDiv.style.color = '#ffb4ab';
          errDiv.textContent = $quotedStartErrPrefix + (e && e.message ? e.message : String(e));
          container.appendChild(errDiv);
        }
      }
    })();
  </script>
</body>
</html>
""".trimIndent()
}

@Composable
private fun PluginStoreSubpage(
    catalogPlugins: List<CatalogPlugin>,
    sdPlugins: List<SdPluginInfo>,
    isLoadingCatalog: Boolean,
    installingPluginName: String?,
    isConnected: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedFilter: Int,
    onSelectedFilterChange: (Int) -> Unit,
    onRefreshCatalog: () -> Unit,
    onInstallPlugin: (CatalogPlugin) -> Unit,
    onConfigurePlugin: (SdPluginInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingThirdPartyInstall by remember { mutableStateOf<CatalogPlugin?>(null) }

    val filteredCatalog = remember(catalogPlugins, sdPlugins, searchQuery, selectedFilter) {
        catalogPlugins.filter { cat ->
            val isInstalled = sdPlugins.any { it.folderName.equals(cat.name, ignoreCase = true) }
            val matchesFilter = when (selectedFilter) {
                1 -> isInstalled
                2 -> !isInstalled
                else -> true
            }
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isBlank() ||
                    cat.title.lowercase().contains(q) ||
                    cat.name.lowercase().contains(q) ||
                    cat.description.lowercase().contains(q) ||
                    cat.author.lowercase().contains(q)
            matchesFilter && matchesSearch
        }
    }

    val installedCount = remember(catalogPlugins, sdPlugins) {
        catalogPlugins.count { cat -> sdPlugins.any { it.folderName.equals(cat.name, ignoreCase = true) } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Search & Filter Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.dev_search_plugins_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.main_clear_search), modifier = Modifier.size(18.dp))
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { onSelectedFilterChange(0) },
                    label = { Text(stringResource(R.string.dev_filter_all, catalogPlugins.size)) }
                )
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { onSelectedFilterChange(1) },
                    label = { Text(stringResource(R.string.dev_filter_installed, installedCount)) }
                )
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { onSelectedFilterChange(2) },
                    label = { Text(stringResource(R.string.dev_filter_available)) }
                )
            }
        }

        if (isLoadingCatalog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dev_loading_catalog),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (catalogPlugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.dev_catalog_empty_or_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(onClick = onRefreshCatalog, shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dev_btn_retry))
                    }
                }
            }
        } else if (filteredCatalog.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.dev_no_plugins_matching_filter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredCatalog, key = { it.name }) { catPlugin ->
                    val installedPlugin = sdPlugins.firstOrNull { it.folderName.equals(catPlugin.name, ignoreCase = true) }
                    val isInstalled = installedPlugin != null
                    val hasUpdate = isInstalled &&
                            installedPlugin!!.version.isNotBlank() &&
                            catPlugin.version.isNotBlank() &&
                            installedPlugin.version != catPlugin.version
                    val isInstalling = installingPluginName == catPlugin.name

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Top Row: Icon + Title/Author + Status Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isInstalled) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Extension,
                                        contentDescription = null,
                                        tint = if (isInstalled) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = catPlugin.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val authorText = catPlugin.author.ifBlank { stringResource(R.string.dev_unknown_author) }
                                    Text(
                                        text = stringResource(R.string.dev_author_by, authorText, catPlugin.version),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (hasUpdate) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.dev_badge_update_available),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                } else if (isInstalled) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = stringResource(R.string.dev_badge_installed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Middle: Description + Source Host + Files
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = catPlugin.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val sourceHost = remember(catPlugin.base) { extractHost(catPlugin.base) }
                            val isThirdPartyHost = sourceHost.isNotBlank() && !isOfficialGithubHost(sourceHost)
                            if (sourceHost.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isThirdPartyHost) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.dev_plugin_source_host, sourceHost),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isThirdPartyHost) {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (isThirdPartyHost) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                text = stringResource(R.string.dev_plugin_third_party_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (catPlugin.files.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = catPlugin.files.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }

                            // Bottom: Action buttons
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (installedPlugin != null) {
                                    OutlinedButton(
                                        onClick = { onConfigurePlugin(installedPlugin) },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(stringResource(R.string.dev_btn_configure))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                FilledTonalButton(
                                    onClick = {
                                        if (!isOfficialGithubHost(sourceHost)) {
                                            pendingThirdPartyInstall = catPlugin
                                        } else {
                                            onInstallPlugin(catPlugin)
                                        }
                                    },
                                    enabled = !isInstalling && isConnected,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isInstalling) {
                                        LoadingIndicator(modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.dev_btn_installing), style = MaterialTheme.typography.labelMedium)
                                    } else {
                                        Text(
                                            text = when {
                                                hasUpdate -> stringResource(R.string.dev_btn_update)
                                                isInstalled -> stringResource(R.string.dev_btn_reinstall)
                                                else -> stringResource(R.string.dev_btn_install)
                                            },
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingThirdPartyInstall?.let { pending ->
        val externalDomain = extractHost(pending.base).ifBlank { pending.base }
        AlertDialog(
            onDismissRequest = { pendingThirdPartyInstall = null },
            title = { Text(stringResource(R.string.dev_plugin_third_party_dialog_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dev_plugin_third_party_dialog_msg, pending.title, externalDomain)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingThirdPartyInstall = null
                        onInstallPlugin(pending)
                    }
                ) {
                    Text(stringResource(R.string.dev_plugin_third_party_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingThirdPartyInstall = null }) {
                    Text(stringResource(R.string.dev_btn_cancel))
                }
            }
        )
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginConfigSubpage(
    plugin: SdPluginInfo,
    currentBaseUrl: String,
    onSaveConfig: (String) -> Unit,
    modifier: Modifier = Modifier,
    deviceStatus: CrossPointStatus? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = CrossSendApp.instance
    val isDarkTheme = isSystemInDarkTheme()
    val hasPluginJs = !plugin.pluginJs.isNullOrBlank()

    var showJsonEditor by remember(plugin.id) { mutableStateOf(!hasPluginJs) }
    var allowCryptoOverride by remember(plugin.id) { mutableStateOf(false) }
    var configText by remember(plugin.id, plugin.configJson) {
        mutableStateOf(plugin.configJson ?: "{\n  \n}")
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(plugin.folderName) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val displayCapabilities = remember(plugin) {
            when {
                plugin.permissions.isNotEmpty() -> plugin.permissions
                plugin.folderName.equals("protected-content", ignoreCase = true) -> listOf("crypto")
                else -> emptyList()
            }
        }
        val showCryptoConsentToggle = !plugin.hasDeclaredPermissions &&
            !plugin.folderName.equals("protected-content", ignoreCase = true) &&
            hasPluginJs

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (displayCapabilities.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.dev_plugin_cap_label, displayCapabilities.joinToString(", ")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = stringResource(R.string.dev_plugin_cap_none),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (showCryptoConsentToggle) {
                    FilterChip(
                        selected = allowCryptoOverride,
                        onClick = { allowCryptoOverride = !allowCryptoOverride },
                        label = { Text(stringResource(R.string.dev_plugin_cap_allow_crypto), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (hasPluginJs) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !showJsonEditor,
                        onClick = { showJsonEditor = false },
                        label = { Text(stringResource(R.string.dev_chip_web_ui)) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = showJsonEditor,
                        onClick = { showJsonEditor = true },
                        label = { Text(stringResource(R.string.dev_chip_json_editor)) }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (hasPluginJs && !showJsonEditor) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            }
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: android.webkit.WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    // Block any navigation away from the local plugin config HTML while AndroidBridge is attached
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: android.webkit.WebView?,
                                    request: android.webkit.WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    val reqUri = request?.url ?: return null
                                    val scheme = reqUri.scheme?.lowercase() ?: ""
                                    val host = reqUri.host?.lowercase() ?: ""
                                    if (scheme == "data" || scheme == "blob" || (scheme == "https" && host == "plugin.crosssend.local")) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    // Block direct external sub-resource requests; plugins must use AndroidBridge.relay()
                                    return android.webkit.WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Forbidden",
                                        emptyMap(),
                                        java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                            }
                            val bridge = ScopedPluginJsBridge(
                                context = context,
                                resources = resources,
                                app = app,
                                plugin = plugin,
                                deviceBaseUrl = currentBaseUrl,
                                allowCryptoOverride = { allowCryptoOverride }
                            )
                            addJavascriptInterface(bridge, "AndroidBridge")

                            val html = buildPluginHostHtml(plugin.folderName, plugin.pluginJs ?: "", isDarkTheme, deviceStatus)
                            loadDataWithBaseURL("https://plugin.crosssend.local/", html, "text/html", "UTF-8", null)
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Extension,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = plugin.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val authorText = if (plugin.author.isNotBlank()) plugin.author else stringResource(R.string.dev_unknown_author)
                                    Text(
                                        text = stringResource(R.string.dev_version_author, plugin.version, authorText),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (plugin.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = plugin.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val pathDisplay = plugin.configFile ?: "${plugin.parentDir}/${plugin.folderName}/config.json"
                            Text(
                                text = stringResource(R.string.dev_file_label, pathDisplay),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.dev_config_json_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = configText,
                                onValueChange = { configText = it },
                                label = { Text("config.json") },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 8,
                                maxLines = 20,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val formatted = JSONObject(configText).toString(2)
                                            configText = formatted
                                        } catch (_: Exception) {
                                            try {
                                                val formatted = JSONArray(configText).toString(2)
                                                configText = formatted
                                            } catch (_: Exception) {
                                                Toast.makeText(context, resources.getString(R.string.dev_invalid_json), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(stringResource(R.string.dev_btn_format))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onSaveConfig(configText) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.dev_btn_save))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DeviceWebUiSubpage(
    webUiUrl: String,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView?) -> Unit = {}
) {
    val app = CrossSendApp.instance
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            onWebViewCreated(null)
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewRef = this
                    onWebViewCreated(this)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    webViewClient = object : WebViewClient() {
                        private var cachedApNet: android.net.Network? = null
                        private var cachedApClient: okhttp3.OkHttpClient? = null
                        private val targetHost = try { Uri.parse(webUiUrl).host?.lowercase() } catch (_: Exception) { null }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUri = request?.url ?: return true
                            val scheme = reqUri.scheme?.lowercase() ?: ""
                            val reqHost = reqUri.host?.lowercase()?.removeSurrounding("[", "]")
                            val baseHost = targetHost?.removeSurrounding("[", "]")
                            val apNet = app.wifiManager.getApNetwork()
                            val allowed = (scheme == "http" || scheme == "https") &&
                                baseHost != null &&
                                reqHost == baseHost &&
                                isLocalDeviceHost(baseHost, apNet)
                            return !allowed
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request == null) return null
                            val scheme = request.url.scheme?.lowercase() ?: ""
                            if (scheme == "data" || scheme == "blob") {
                                return super.shouldInterceptRequest(view, request)
                            }
                            val apNet = app.wifiManager.getApNetwork()
                            val reqHost = request.url.host?.lowercase()?.removeSurrounding("[", "]")
                            val baseHost = targetHost?.removeSurrounding("[", "]")
                            if ((scheme != "http" && scheme != "https") || baseHost == null || reqHost != baseHost || !isLocalDeviceHost(baseHost, apNet)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            if (apNet != null && request.method.equals("GET", ignoreCase = true)) {
                                try {
                                    val client = if (cachedApNet == apNet && cachedApClient != null) {
                                        cachedApClient!!
                                    } else {
                                        okhttp3.OkHttpClient.Builder()
                                            .followRedirects(false)
                                            .followSslRedirects(false)
                                            .socketFactory(apNet.socketFactory)
                                            .dns(object : okhttp3.Dns {
                                                override fun lookup(hostname: String): List<java.net.InetAddress> {
                                                    val addrs = try {
                                                        apNet.getAllByName(hostname).toList()
                                                    } catch (_: Exception) {
                                                        java.net.InetAddress.getAllByName(hostname).toList()
                                                    }
                                                    val localAddrs = addrs.filter { isAllowedLocalAddress(it) }
                                                    if (localAddrs.isEmpty()) {
                                                        throw java.net.UnknownHostException("Host is not a local device address: $hostname")
                                                    }
                                                    return localAddrs
                                                }
                                            })
                                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                            .build().also {
                                                cachedApNet = apNet
                                                cachedApClient = it
                                            }
                                    }

                                    val reqBuilder = okhttp3.Request.Builder()
                                        .url(request.url.toString())
                                        .get()

                                    request.requestHeaders?.forEach { (k, v) ->
                                        reqBuilder.addHeader(k, v)
                                    }

                                    val resp = client.newCall(reqBuilder.build()).execute()
                                    if (resp.isSuccessful && resp.body != null) {
                                        val contentTypeHeader = resp.header("Content-Type") ?: "text/html"
                                        val mimeType = contentTypeHeader.substringBefore(';').trim()
                                        val encoding = if (contentTypeHeader.contains("charset=", ignoreCase = true)) {
                                            contentTypeHeader.substringAfter("charset=", "UTF-8").substringBefore(';').trim()
                                        } else {
                                            "UTF-8"
                                        }
                                        val headersMap = mutableMapOf<String, String>()
                                        for (i in 0 until resp.headers.size) {
                                            headersMap[resp.headers.name(i)] = resp.headers.value(i)
                                        }
                                        return WebResourceResponse(
                                            mimeType,
                                            encoding,
                                            resp.code,
                                            if (resp.message.isBlank()) "OK" else resp.message,
                                            headersMap,
                                            resp.body!!.byteStream()
                                        )
                                    } else {
                                        resp.close()
                                    }
                                } catch (_: Exception) {
                                    // Fall through
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl(webUiUrl)
                }
            }
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(modifier = Modifier.size(36.dp))
            }
        }
    }
}
