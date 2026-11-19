@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.crosssend.app.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.ui.components.CrossSendSearchInputField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private const val PLUGIN_CATALOG_URL = "https://raw.githubusercontent.com/itsthisjustin/sd-plugins/main/catalog.json"
private const val PROTECTED_CONTENT_BASE_URL = "https://protected-content-host-production.up.railway.app/"
private const val MAX_PLUGIN_FILE_BYTES = 5L * 1024 * 1024

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

internal fun extractHost(url: String): String {
    return try {
        URI(url.trim()).host?.lowercase(Locale.ROOT).orEmpty()
    } catch (_: Exception) {
        ""
    }
}

internal fun isOfficialGithubHost(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT).trim()
    return normalized == "github.com" || normalized == "raw.githubusercontent.com"
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

internal fun parseSha256Map(obj: JSONObject?): Map<String, String> {
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

internal fun parseManifestHashes(manifestJson: String?): Map<String, String> {
    if (manifestJson.isNullOrBlank()) return emptyMap()
    return try {
        parseSha256Map(JSONObject(manifestJson))
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * Merges SHA-256 digests from the plugin catalog and `manifest.json`, giving catalog digests
 * strict priority and rejecting any conflicting non-empty digest for the same file.
 */
internal fun mergeExpectedPluginHashes(
    catalogHashes: Map<String, String>,
    manifestHashes: Map<String, String>
): Map<String, String> {
    val normalizedCatalog = buildMap {
        for ((rawKey, rawVal) in catalogHashes) {
            val key = rawKey.trim().lowercase(Locale.ROOT)
            val hash = rawVal.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty() && hash.isNotEmpty()) {
                put(key, hash)
            }
        }
    }
    val normalizedManifest = buildMap {
        for ((rawKey, rawVal) in manifestHashes) {
            val key = rawKey.trim().lowercase(Locale.ROOT)
            val hash = rawVal.trim().lowercase(Locale.ROOT)
            if (key.isNotEmpty() && hash.isNotEmpty()) {
                put(key, hash)
            }
        }
    }
    for ((key, catalogHash) in normalizedCatalog) {
        val manifestHash = normalizedManifest[key]
        if (manifestHash != null && manifestHash != catalogHash) {
            throw SecurityException("Conflicting SHA-256 hash in manifest for $key")
        }
    }
    return normalizedManifest + normalizedCatalog
}

private fun buildCatalogHttpsClient(app: CrossSendApp): OkHttpClient {
    return app.wifiManager.getInternetOkHttpClientBuilder()
        .addInterceptor { chain ->
            val reqUrl = chain.request().url.toString()
            if (!EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrl)) {
                throw IOException("Unsafe or non-HTTPS URL")
            }
            val resp = chain.proceed(chain.request())
            if (!EInkImageOptimizer.isSafeHttpsExternalUrl(resp.request.url.toString())) {
                resp.close()
                throw IOException("Unsafe or non-HTTPS redirect")
            }
            resp
        }
        .build()
}

private fun buildProtectedContentFallback(description: String): CatalogPlugin {
    return CatalogPlugin(
        name = "protected-content",
        title = "Protected Content",
        description = description,
        author = "Diirge",
        version = "1.0.5",
        base = PROTECTED_CONTENT_BASE_URL,
        files = listOf("manifest.json", "plugin.js")
    )
}

/**
 * Fetches the official CrossPoint SD plugin catalog over HTTPS and ensures the
 * Protected Content entry is present even if the remote catalog cannot be reached.
 */
internal suspend fun fetchPluginCatalog(
    app: CrossSendApp,
    protectedContentDescription: String
): List<CatalogPlugin> {
    val list = mutableListOf<CatalogPlugin>()
    try {
        val client = buildCatalogHttpsClient(app)
        val req = Request.Builder()
            .url(PLUGIN_CATALOG_URL)
            .get()
            .build()
        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
        resp.use { r ->
            if (r.isSuccessful) {
                val bodyStr = r.body?.let { EInkImageOptimizer.readBoundedString(it, 2L * 1024 * 1024) }.orEmpty()
                val json = JSONObject(bodyStr)
                val arr = json.getJSONArray("plugins")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pluginName = obj.getString("name").trim()
                    if (pluginName.isBlank()) continue
                    val baseUrl = obj.getString("base").trim()
                    if (!EInkImageOptimizer.isSafeHttpsExternalUrl(baseUrl)) continue
                    val filesArr = obj.optJSONArray("files")
                    val filesList = mutableListOf<String>()
                    if (filesArr != null) {
                        for (j in 0 until filesArr.length()) {
                            filesList.add(filesArr.getString(j))
                        }
                    }
                    list.add(
                        CatalogPlugin(
                            name = pluginName,
                            title = obj.optString("title", pluginName),
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
    } catch (_: Exception) {
        // Fall back to built-in catalog entry when offline or catalog fetch fails
    }

    if (list.none { it.name.equals("protected-content", true) }) {
        list.add(0, buildProtectedContentFallback(protectedContentDescription))
    }
    return list.distinctBy { it.name.trim().lowercase(Locale.ROOT) }
}

internal sealed interface PluginInstallOutcome {
    data object Success : PluginInstallOutcome
    data object InvalidPluginName : PluginInstallOutcome
    data object UnsafeUrl : PluginInstallOutcome
    data object UploadFailed : PluginInstallOutcome
    data class Error(val message: String) : PluginInstallOutcome
}

/**
 * Downloads plugin files over HTTPS, verifies optional SHA-256 digests from both the catalog
 * and downloaded manifest, and uploads them into the reader's plugin directory.
 */
internal suspend fun installCatalogPluginToDevice(
    app: CrossSendApp,
    resources: Resources,
    plugin: CatalogPlugin,
    existingParentDir: String?
): PluginInstallOutcome {
    val safePluginName = plugin.name.trim()
    if (safePluginName.isBlank() ||
        safePluginName.contains("..") ||
        safePluginName.contains("/") ||
        safePluginName.contains("\\") ||
        safePluginName.lowercase(Locale.ROOT) in RESERVED_PLUGIN_NAMES
    ) {
        return PluginInstallOutcome.InvalidPluginName
    }
    val cleanBase = plugin.base.trim()
    if (!EInkImageOptimizer.isSafeHttpsExternalUrl(cleanBase)) {
        return PluginInstallOutcome.UnsafeUrl
    }

    return try {
        val ok = withContext(Dispatchers.IO) {
            val client = buildCatalogHttpsClient(app)
            val downloadedFiles = mutableListOf<Triple<String, String, String>>()
            var manifestContent: String? = null

            for (fileName in plugin.files) {
                val cleanFile = fileName.trim()
                if (cleanFile.isBlank() || cleanFile.contains("..") || cleanFile.contains("/") || cleanFile.contains("\\")) {
                    continue
                }
                val fileUrl = "${cleanBase.trimEnd('/')}/$cleanFile"
                if (!EInkImageOptimizer.isSafeHttpsExternalUrl(fileUrl)) {
                    throw SecurityException("Unsafe or non-HTTPS plugin file URL")
                }
                val req = Request.Builder().url(fileUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} for $cleanFile")
                    }
                    val bytes = resp.body?.source()?.let { src ->
                        src.request(MAX_PLUGIN_FILE_BYTES + 1)
                        if (src.buffer.size > MAX_PLUGIN_FILE_BYTES) {
                            throw IOException("Plugin file exceeds 5 MB limit")
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

            val expectedHashes = mergeExpectedPluginHashes(
                catalogHashes = plugin.sha256,
                manifestHashes = parseManifestHashes(manifestContent)
            )
            for ((cleanFile, _, actualSha) in downloadedFiles) {
                val expectedSha = expectedHashes[cleanFile.trim().lowercase(Locale.ROOT)]
                if (!expectedSha.isNullOrBlank() && !actualSha.equals(expectedSha, ignoreCase = true)) {
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

            var allUploaded = true
            for ((cleanFile, content, _) in downloadedFiles) {
                if (!app.webDavClient.writeTextFile("$targetDir/$cleanFile", content)) {
                    allUploaded = false
                }
            }
            allUploaded
        }
        if (ok) PluginInstallOutcome.Success else PluginInstallOutcome.UploadFailed
    } catch (e: Exception) {
        PluginInstallOutcome.Error(e.message.orEmpty())
    }
}

/**
 * Displays the searchable CrossPoint SD plugin catalog with filter chips, third-party host
 * warnings, install/update actions, and direct navigation to plugin configuration.
 */
@Composable
internal fun PluginStoreSubpage(
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
    val searchTextFieldState = rememberTextFieldState(initialText = searchQuery)
    val searchBarState = rememberSearchBarState()

    LaunchedEffect(searchQuery) {
        if (searchTextFieldState.text.toString() != searchQuery) {
            searchTextFieldState.setTextAndPlaceCursorAtEnd(searchQuery)
        }
    }
    LaunchedEffect(searchTextFieldState.text) {
        val current = searchTextFieldState.text.toString()
        if (current != searchQuery) {
            onSearchQueryChange(current)
        }
    }

    val locale = LocalLocale.current.platformLocale
    val uniqueCatalog = remember(catalogPlugins) {
        catalogPlugins.distinctBy { it.name.trim().lowercase(Locale.ROOT) }
    }

    val filteredCatalog = remember(uniqueCatalog, sdPlugins, searchQuery, selectedFilter, locale) {
        uniqueCatalog.filter { cat ->
            val isInstalled = sdPlugins.any { it.folderName.equals(cat.name, ignoreCase = true) }
            val matchesFilter = when (selectedFilter) {
                1 -> isInstalled
                2 -> !isInstalled
                else -> true
            }
            val q = searchQuery.trim().lowercase(locale)
            val matchesSearch = q.isBlank() ||
                cat.title.lowercase(locale).contains(q) ||
                cat.name.lowercase(locale).contains(q) ||
                cat.description.lowercase(locale).contains(q) ||
                cat.author.lowercase(locale).contains(q)
            matchesFilter && matchesSearch
        }
    }

    val installedCount = remember(uniqueCatalog, sdPlugins) {
        uniqueCatalog.count { cat -> sdPlugins.any { it.folderName.equals(cat.name, ignoreCase = true) } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            CrossSendSearchInputField(
                textFieldState = searchTextFieldState,
                searchBarState = searchBarState,
                placeholderText = stringResource(R.string.dev_search_plugins_placeholder),
                modifier = Modifier.fillMaxWidth(),
                onClearSearch = { onSearchQueryChange("") }
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
                    label = { Text(stringResource(R.string.dev_filter_all, uniqueCatalog.size)) }
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
        } else if (uniqueCatalog.isEmpty()) {
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
                    FilledTonalButton(onClick = onRefreshCatalog, shape = MaterialTheme.shapes.small) {
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
                    val hasUpdate = installedPlugin != null &&
                        installedPlugin.version.isNotBlank() &&
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(MaterialTheme.shapes.small)
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
                                        shape = MaterialTheme.shapes.extraSmall,
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
                                        shape = MaterialTheme.shapes.extraSmall,
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

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (installedPlugin != null) {
                                    OutlinedButton(
                                        onClick = { onConfigurePlugin(installedPlugin) },
                                        shape = MaterialTheme.shapes.small
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
                                    shape = MaterialTheme.shapes.small
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
