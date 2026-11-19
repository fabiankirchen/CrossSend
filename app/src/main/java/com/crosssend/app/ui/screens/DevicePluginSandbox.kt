@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.crosssend.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.crosssend.app.CrossSendApp
import com.crosssend.app.R
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.network.CrossPointStatus
import com.crosssend.app.network.LocalDeviceHostValidator
import com.crosssend.app.network.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val BRIDGE_LEGACY_TIMEOUT_MS = 20_000L
private const val MAX_BRIDGE_PAYLOAD_BYTES = 5 * 1024 * 1024
private const val MAX_RELAY_RESPONSE_BYTES = 10L * 1024 * 1024
private const val MAX_WEB_UI_RESPONSE_BYTES = 25L * 1024 * 1024

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

private val BLOCKED_RELAY_HEADERS = setOf(
    "host",
    "connection",
    "content-length",
    "transfer-encoding",
    "upgrade",
    "te",
    "trailer",
    "proxy-authorization",
    "proxy-connection"
)

internal fun parseManifestPermissions(manifestJson: String?): Pair<Boolean, List<String>> {
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
                    val item = arr.optString(i, "").trim().lowercase(Locale.ROOT)
                    if (item.isNotEmpty()) collected.add(item)
                }
            } else if (json.has(field)) {
                val raw = json.optString(field, "")
                raw.split(',')
                    .map { it.trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
                    .forEach { collected.add(it) }
            }
        }
        true to collected.toList()
    } catch (_: Exception) {
        false to emptyList()
    }
}

internal fun isAllowedPluginConfigPath(folderName: String, parentDir: String, candidatePath: String): Boolean {
    val cleanFolder = folderName.trim().trim('/', '\\')
    if (cleanFolder.isEmpty() || cleanFolder == "." || cleanFolder == ".." || cleanFolder.lowercase(Locale.ROOT) in RESERVED_PLUGIN_NAMES) {
        return false
    }
    val normalized = WebDavClient.sanitizeRemotePath(candidatePath)
    if (normalized == "/" || normalized.lowercase(Locale.ROOT) in PROTECTED_CROSSPOINT_CONFIG_FILES) return false

    val parentPrefix = "${WebDavClient.sanitizeRemotePath(parentDir).trimEnd('/')}/$cleanFolder/"
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
        add("/.crosspoint/$cleanFolder.json".lowercase(Locale.ROOT))
        add("/.crosspoint/$hyphenVariant.json".lowercase(Locale.ROOT))
        add("/.crosspoint/$underscoreVariant.json".lowercase(Locale.ROOT))
        if (cleanFolder.equals("monthwallpaper", ignoreCase = true)) {
            add("/.crosspoint/month-wallpaper.json")
        }
    }
    return normalized.lowercase(Locale.ROOT) in allowedRootConfigs
}

/**
 * Resolves a plugin-scoped SD card path and enforces namespace isolation plus write protection
 * against modifying executable code or permission manifests (`manifest.json`, `plugin.js`,
 * `device.json`, or any `.js` file).
 */
internal fun resolvePluginScopedPath(
    folderName: String,
    parentDir: String,
    path: String,
    forWrite: Boolean = false
): String {
    val clean = path.trim()
    if (clean.isEmpty()) throw SecurityException("Empty path not allowed")
    if (clean.any { it == '\u0000' || it.isISOControl() }) {
        throw SecurityException("Control characters not allowed in path")
    }

    val segments = clean.split("/", "\\").filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
        throw SecurityException("Path traversal blocked: $path")
    }

    val cleanFolder = folderName.trim().trim('/', '\\')
    if (cleanFolder.isEmpty() ||
        cleanFolder == "." ||
        cleanFolder == ".." ||
        cleanFolder.contains('/') ||
        cleanFolder.contains('\\') ||
        cleanFolder.lowercase(Locale.ROOT) in RESERVED_PLUGIN_NAMES
    ) {
        throw SecurityException("Reserved plugin folder name")
    }

    val normalizedParent = WebDavClient.sanitizeRemotePath(parentDir).trimEnd('/')
    val pluginDirPrefix = "$normalizedParent/$cleanFolder/"
    val altPluginDirPrefix = "/plugins/$cleanFolder/"
    val dotCrosspointPluginDirPrefix = "/.crosspoint/plugins/$cleanFolder/"
    val allowedDotCrosspointJson = "/.crosspoint/$cleanFolder.json"

    val resolved = if (!clean.startsWith("/") && !clean.startsWith("\\")) {
        val candidate = "$normalizedParent/$cleanFolder/${segments.joinToString("/")}"
        if (candidate.lowercase(Locale.ROOT) in PROTECTED_CROSSPOINT_CONFIG_FILES) {
            throw SecurityException("Access to protected CrossPoint system file denied")
        }
        if (!candidate.startsWith(pluginDirPrefix)) {
            throw SecurityException("Access denied: path outside plugin directory ($candidate)")
        }
        candidate
    } else {
        val normalized = "/" + segments.joinToString("/")
        if (normalized.lowercase(Locale.ROOT) in PROTECTED_CROSSPOINT_CONFIG_FILES) {
            throw SecurityException("Access to protected CrossPoint system file denied")
        }
        val isInPluginNamespace = normalized.startsWith(pluginDirPrefix) ||
            normalized.startsWith(altPluginDirPrefix) ||
            normalized.startsWith(dotCrosspointPluginDirPrefix) ||
            normalized.equals(allowedDotCrosspointJson, ignoreCase = true) ||
            isAllowedPluginConfigPath(cleanFolder, parentDir, normalized)
        if (!isInPluginNamespace) {
            throw SecurityException("Access denied: path outside plugin directory ($normalized)")
        }
        normalized
    }

    if (forWrite) {
        val targetFileName = resolved.substringAfterLast('/').trim().lowercase(Locale.ROOT)
        if (targetFileName == "manifest.json" ||
            targetFileName == "plugin.js" ||
            targetFileName == "device.json" ||
            targetFileName.endsWith(".js") ||
            targetFileName.endsWith(".mjs") ||
            targetFileName.endsWith(".cjs")
        ) {
            throw SecurityException("Write access to protected plugin file denied: $resolved")
        }
    }

    return resolved
}

/**
 * Parses and sanitizes HTTP headers supplied by a plugin before forwarding via `relay` or
 * `fetchToSd`. Strips CR/LF characters, drops blank or invalid header keys, and blocks
 * hop-by-hop or sensitive override headers.
 */
internal fun filterRelayHeaders(headersJson: String): Map<String, String> {
    if (headersJson.isBlank()) return emptyMap()
    val trimmed = headersJson.trim()
    if (trimmed == "null" || trimmed == "undefined") return emptyMap()

    return try {
        val headersObj = JSONObject(trimmed)
        val filtered = linkedMapOf<String, String>()
        val keys = headersObj.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val cleanKey = rawKey
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u0000", "")
                .trim()
            if (cleanKey.isBlank()) continue
            if (cleanKey.any { it <= ' ' || it >= '\u007f' || it == ':' }) continue
            if (cleanKey.lowercase(Locale.ROOT) in BLOCKED_RELAY_HEADERS) continue
            if (headersObj.isNull(rawKey)) continue

            val cleanValue = headersObj.optString(rawKey, "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\u0000", "")
                .trim()
            filtered[cleanKey] = cleanValue
        }
        filtered
    } catch (_: Exception) {
        emptyMap()
    }
}

private val ALLOWED_PLUGIN_DEVICE_RELAY_PATHS = setOf(
    "/api/status",
    "/api/plugins",
    "/api/plugin-jobs"
)

internal fun isAllowedLocalAddress(addr: InetAddress): Boolean =
    LocalDeviceHostValidator.isAllowedLocalAddress(addr)

internal fun isLocalDeviceHost(host: String, apNet: Network? = null): Boolean =
    LocalDeviceHostValidator.isLocalDeviceHost(host, apNet)

/**
 * Restricts plugin-initiated local device relays to read-only `GET` requests against
 * explicitly allowlisted status and job endpoints on the configured CrossPoint host.
 */
internal fun isAllowedPluginDeviceRelay(
    method: String,
    url: HttpUrl,
    deviceBaseUrl: String
): Boolean {
    if (!method.trim().equals("GET", ignoreCase = true)) return false
    val deviceUri = deviceBaseUrl.trim().toHttpUrlOrNull() ?: return false
    if (url.scheme != "http" || deviceUri.scheme != "http") return false
    val reqHost = url.host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
    val deviceHost = deviceUri.host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
    if (reqHost != deviceHost || url.port != deviceUri.port) return false
    if (!isLocalDeviceHost(reqHost)) return false
    val normalizedPath = url.encodedPath.trimEnd('/').ifEmpty { "/" }
    return normalizedPath in ALLOWED_PLUGIN_DEVICE_RELAY_PATHS
}

/**
 * String overload of [isAllowedPluginDeviceRelay] for bridge callers and unit tests.
 */
internal fun isAllowedPluginDeviceRelay(
    method: String,
    url: String,
    deviceBaseUrl: String
): Boolean {
    val parsedUrl = url.trim().toHttpUrlOrNull() ?: return false
    return isAllowedPluginDeviceRelay(method, parsedUrl, deviceBaseUrl)
}

private class BoundedResponseInputStream(
    private val response: Response,
    private val maxBytes: Long
) : FilterInputStream(response.body!!.byteStream()) {
    private var bytesRead = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            bytesRead++
            if (bytesRead > maxBytes) {
                throw IOException("Device Web UI response exceeds $maxBytes byte limit")
            }
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val remaining = maxBytes - bytesRead
        val toRead = if (remaining > 0L) minOf(len.toLong(), remaining).toInt() else 1
        val count = super.read(b, off, toRead)
        if (count > 0) {
            bytesRead += count
            if (bytesRead > maxBytes) {
                throw IOException("Device Web UI response exceeds $maxBytes byte limit")
            }
        }
        return count
    }

    override fun close() {
        try {
            super.close()
        } finally {
            response.close()
        }
    }
}

internal class ScopedPluginJsBridge(
    private val context: Context,
    private val resources: Resources,
    private val app: CrossSendApp,
    private val plugin: SdPluginInfo,
    private val deviceBaseUrl: String,
    private val bridgeScope: CoroutineScope,
    private val webViewProvider: () -> WebView? = { null },
    private val allowCryptoOverride: () -> Boolean = { false }
) {
    @Volatile
    private var cachedDeviceNetwork: Network? = null

    @Volatile
    private var cachedDeviceHost: String? = null

    @Volatile
    private var cachedDeviceClient: OkHttpClient? = null
    private val clientLock = Any()

    private fun resolveDeviceHttpUrlIfAllowed(method: String, url: String): OkHttpClient? {
        if (!isAllowedPluginDeviceRelay(method, url, deviceBaseUrl)) return null
        val deviceHost = deviceBaseUrl.trim().toHttpUrlOrNull()
            ?.host
            ?.lowercase(Locale.ROOT)
            ?.removeSurrounding("[", "]")
            ?: return null

        val apNet = app.wifiManager.getApNetwork()
        if (!isLocalDeviceHost(deviceHost, apNet)) return null

        synchronized(clientLock) {
            val existing = cachedDeviceClient
            if (existing != null && cachedDeviceNetwork == apNet && cachedDeviceHost == deviceHost) {
                return existing
            }
            val deviceRelayInterceptor = Interceptor { chain ->
                val req = chain.request()
                val reqUri = req.url
                val reqUriHost = reqUri.host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
                if (!isAllowedPluginDeviceRelay(req.method, reqUri, deviceBaseUrl) ||
                    !isLocalDeviceHost(reqUriHost, apNet)
                ) {
                    throw IOException("Disallowed or unsafe device relay request")
                }
                val resp = chain.proceed(req)
                val finalUrl = resp.request.url
                val finalHost = finalUrl.host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
                if (!isAllowedPluginDeviceRelay(resp.request.method, finalUrl, deviceBaseUrl) ||
                    !isLocalDeviceHost(finalHost, apNet)
                ) {
                    resp.close()
                    throw IOException("Unsafe device redirect")
                }
                if (resp.isRedirect) {
                    val location = resp.header("Location")
                    if (location != null) {
                        val resolved = finalUrl.resolve(location)
                        val resolvedHost = resolved?.host?.lowercase(Locale.ROOT)?.removeSurrounding("[", "]")
                        if (resolved == null ||
                            resolvedHost == null ||
                            !isAllowedPluginDeviceRelay(resp.request.method, resolved, deviceBaseUrl) ||
                            !isLocalDeviceHost(resolvedHost, apNet)
                        ) {
                            resp.close()
                            throw IOException("Unsafe device redirect Location")
                        }
                    }
                }
                resp
            }

            val builder = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
            if (apNet != null) {
                builder.socketFactory(apNet.socketFactory)
            }
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val clean = hostname.lowercase(Locale.ROOT).trim().removeSurrounding("[", "]")
                    if (clean != deviceHost) {
                        throw UnknownHostException("Blocked non-device host: $hostname")
                    }
                    val addrs = if (apNet != null) {
                        try {
                            apNet.getAllByName(hostname).toList()
                        } catch (_: Exception) {
                            InetAddress.getAllByName(hostname).toList()
                        }
                    } else {
                        InetAddress.getAllByName(hostname).toList()
                    }
                    val localAddrs = addrs.filter { isAllowedLocalAddress(it) }
                    if (localAddrs.isEmpty()) {
                        throw UnknownHostException("Host is not a local device address: $hostname")
                    }
                    return localAddrs
                }
            })
                .addInterceptor(deviceRelayInterceptor)
                .addNetworkInterceptor(deviceRelayInterceptor)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)

            return builder.build().also {
                cachedDeviceNetwork = apNet
                cachedDeviceHost = deviceHost
                cachedDeviceClient = it
            }
        }
    }

    private fun buildHttpsInternetClient(): OkHttpClient {
        return EInkImageOptimizer.getSafeInternetClient(
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 15,
            followRedirects = true,
            extraNetworkInterceptor = EXTERNAL_HTTPS_GUARD_INTERCEPTOR
        )
    }

    private fun resolveScopedPath(path: String, forWrite: Boolean = false): String {
        return resolvePluginScopedPath(
            folderName = plugin.folderName,
            parentDir = plugin.parentDir,
            path = path,
            forWrite = forWrite
        )
    }

    private fun isCryptoPermitted(): Boolean {
        if (plugin.hasDeclaredPermissions && "crypto" in plugin.permissions) {
            return true
        }
        return allowCryptoOverride()
    }

    private suspend fun readFileSuspend(path: String): String {
        return try {
            val normalized = resolveScopedPath(path, forWrite = false)
            withContext(Dispatchers.IO) {
                app.webDavClient.readTextFile(normalized).orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun writeFileInternalSuspend(path: String, data: String, isBase64: Boolean): Boolean {
        return try {
            if (data.length > 7 * 1024 * 1024) return false
            val normalized = resolveScopedPath(path, forWrite = true)
            val content = if (isBase64) {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                if (bytes.size > MAX_BRIDGE_PAYLOAD_BYTES) return false
                String(bytes, Charsets.UTF_8)
            } else {
                if (data.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_PAYLOAD_BYTES) return false
                data
            }
            val success = withContext(Dispatchers.IO) {
                app.webDavClient.writeTextFile(normalized, content)
            }
            if (success) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, resources.getString(R.string.dev_settings_saved), Toast.LENGTH_SHORT).show()
                }
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun relaySuspend(method: String, url: String, headersJson: String, body: String): String {
        val trimmedUrl = url.trim()
        val upperMethod = method.trim().uppercase(Locale.ROOT).ifBlank { "GET" }
        val deviceLanClient = resolveDeviceHttpUrlIfAllowed(upperMethod, trimmedUrl)
        if (deviceLanClient == null) {
            val parsedUrl = trimmedUrl.toHttpUrlOrNull()
            if (parsedUrl == null ||
                !parsedUrl.isHttps ||
                !EInkImageOptimizer.isSafeHttpsExternalUrl(trimmedUrl) ||
                isLocalDeviceHost(parsedUrl.host)
            ) {
                return JSONObject().put("status", 403).put("error", "SSRF or non-HTTPS URL blocked").toString()
            }
        }
        return try {
            withContext(Dispatchers.IO) {
                val client = deviceLanClient ?: buildHttpsInternetClient()
                val reqBuilder = Request.Builder().url(trimmedUrl)
                val safeHeaders = filterRelayHeaders(headersJson)
                safeHeaders.forEach { (key, value) ->
                    reqBuilder.addHeader(key, value)
                }
                val reqBody = if (upperMethod in listOf("POST", "PUT", "PATCH")) {
                    val mediaTypeStr = safeHeaders.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value
                        ?.takeIf { it.isNotBlank() }
                    val mediaType = mediaTypeStr?.toMediaType() ?: "application/json; charset=utf-8".toMediaType()
                    body.toRequestBody(mediaType)
                } else {
                    null
                }

                reqBuilder.method(upperMethod, reqBody)
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val bytes = resp.body?.source()?.let { src ->
                        src.request(MAX_RELAY_RESPONSE_BYTES + 1)
                        if (src.buffer.size > MAX_RELAY_RESPONSE_BYTES) {
                            throw IOException("Response exceeds 10 MB limit")
                        }
                        src.buffer.readByteArray()
                    } ?: ByteArray(0)
                    val respBody = String(bytes, Charsets.UTF_8)
                    JSONObject().apply {
                        put("status", resp.code)
                        put("body", respBody)
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", 500)
                put("error", e.message ?: "Request failed")
            }.toString()
        }
    }

    private suspend fun fetchToSdSuspend(url: String, dest: String, headersJson: String): String {
        val normalizedDest = try {
            resolveScopedPath(dest, forWrite = true)
        } catch (e: Exception) {
            return JSONObject().put("status", 403).put("error", "Security violation: ${e.message}").toString()
        }
        val trimmedUrl = url.trim()
        val parsedUrl = trimmedUrl.toHttpUrlOrNull()
        if (parsedUrl == null ||
            !parsedUrl.isHttps ||
            !EInkImageOptimizer.isSafeHttpsExternalUrl(trimmedUrl) ||
            isLocalDeviceHost(parsedUrl.host)
        ) {
            return JSONObject().put("status", 403).put("error", "Security violation: public HTTPS URL required").toString()
        }
        return try {
            withContext(Dispatchers.IO) {
                val client = buildHttpsInternetClient()
                val reqBuilder = Request.Builder().url(trimmedUrl).get()
                filterRelayHeaders(headersJson).forEach { (key, value) ->
                    reqBuilder.addHeader(key, value)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext JSONObject().put("status", resp.code).put("error", "HTTP ${resp.code}").toString()
                    }
                    val bytes = resp.body?.source()?.let { src ->
                        src.request(MAX_RELAY_RESPONSE_BYTES + 1)
                        if (src.buffer.size > MAX_RELAY_RESPONSE_BYTES) {
                            throw IOException("Response exceeds 10 MB limit")
                        }
                        src.buffer.readByteArray()
                    } ?: ByteArray(0)
                    val success = app.webDavClient.uploadBytes(normalizedDest, bytes)
                    if (success) {
                        JSONObject().put("status", 200).put("success", true).toString()
                    } else {
                        JSONObject().put("status", 500).put("error", "Write to SD failed").toString()
                    }
                }
            }
        } catch (e: Exception) {
            JSONObject().put("status", 500).put("error", e.message ?: "Download failed").toString()
        }
    }

    private suspend fun cryptoSuspend(requestJson: String): String {
        if (!isCryptoPermitted()) {
            return JSONObject()
                .put("status", 403)
                .put("error", "Crypto capability denied for plugin ${plugin.folderName}")
                .toString()
        }
        return try {
            val res = withContext(Dispatchers.IO) {
                app.deviceClient.executeCrypto(deviceBaseUrl, requestJson)
            }
            res.getOrElse { err ->
                JSONObject().put("error", err.message ?: "Crypto failed").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Crypto failed").toString()
        }
    }

    private suspend fun enqueueJobSuspend(pluginName: String, action: String, argsJson: String): String {
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
            val res = withContext(Dispatchers.IO) {
                app.deviceClient.enqueuePluginJob(deviceBaseUrl, targetPlugin, action, parsedArgs)
            }
            res.getOrElse { err ->
                JSONObject().put("error", err.message ?: "Enqueue job failed").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Enqueue job failed").toString()
        }
    }

    /**
     * Dispatches asynchronous bridge calls off the WebView JavaBridge thread and delivers
     * results back to JS via `window.__cpResolve(callId, result)`.
     */
    @JavascriptInterface
    fun invokeAsync(callId: String, op: String, argsJson: String) {
        bridgeScope.launch {
            val result = try {
                val args = if (argsJson.isNotBlank() && argsJson.trim() != "null") {
                    try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
                } else {
                    JSONObject()
                }
                when (op) {
                    "readFile" -> readFileSuspend(args.optString("path", ""))
                    "writeFile" -> writeFileInternalSuspend(
                        path = args.optString("path", ""),
                        data = args.optString("data", ""),
                        isBase64 = args.optBoolean("isBase64", false)
                    ).toString()
                    "relay" -> relaySuspend(
                        method = args.optString("method", "GET"),
                        url = args.optString("url", ""),
                        headersJson = args.optString("headersJson", ""),
                        body = args.optString("body", "")
                    )
                    "fetchToSd" -> fetchToSdSuspend(
                        url = args.optString("url", ""),
                        dest = args.optString("dest", ""),
                        headersJson = args.optString("headersJson", "")
                    )
                    "crypto" -> cryptoSuspend(args.optString("requestJson", "{}"))
                    "enqueueJob" -> enqueueJobSuspend(
                        pluginName = args.optString("pluginName", ""),
                        action = args.optString("action", ""),
                        argsJson = args.optString("argsJson", "")
                    )
                    else -> JSONObject().put("status", 400).put("error", "Unknown bridge operation: $op").toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JSONObject().put("status", 500).put("error", e.message ?: "Bridge call failed").toString()
            }

            if (!isActive) return@launch
            val webView = webViewProvider() ?: return@launch
            webView.post {
                webView.evaluateJavascript(
                    "window.__cpResolve(${JSONObject.quote(callId)}, ${JSONObject.quote(result)})",
                    null
                )
            }
        }
    }

    @JavascriptInterface
    fun readFile(path: String): String {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    readFileSuspend(path)
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun writeFile(path: String, rawData: String): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    writeFileInternalSuspend(path, rawData, isBase64 = false)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun writeFileEncoded(path: String, data: String, isBase64: Boolean): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    writeFileInternalSuspend(path, data, isBase64)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun writeFileWithEncoding(path: String, data: String, isBase64: Boolean): Boolean {
        return writeFileEncoded(path, data, isBase64)
    }

    @JavascriptInterface
    fun relay(method: String, url: String, headersJson: String, body: String): String {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    relaySuspend(method, url, headersJson, body)
                }
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
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    fetchToSdSuspend(url, dest, headersJson)
                }
            }
        } catch (e: Exception) {
            JSONObject().put("status", 500).put("error", e.message ?: "Download failed").toString()
        }
    }

    @JavascriptInterface
    fun crypto(requestJson: String): String {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    cryptoSuspend(requestJson)
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Crypto failed").toString()
        }
    }

    @JavascriptInterface
    fun enqueueJob(pluginName: String, action: String, argsJson: String): String {
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(BRIDGE_LEGACY_TIMEOUT_MS) {
                    enqueueJobSuspend(pluginName, action, argsJson)
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Enqueue job failed").toString()
        }
    }

    companion object {
        private val EXTERNAL_HTTPS_GUARD_INTERCEPTOR = Interceptor { chain ->
            val req = chain.request()
            val reqUrl = req.url.toString()
            if (!req.isHttps ||
                !EInkImageOptimizer.isSafeHttpsExternalUrl(reqUrl) ||
                isLocalDeviceHost(req.url.host)
            ) {
                throw IOException("Unsafe or non-HTTPS URL")
            }
            val resp = chain.proceed(req)
            val finalUrl = resp.request.url
            if (!finalUrl.isHttps ||
                !EInkImageOptimizer.isSafeHttpsExternalUrl(finalUrl.toString()) ||
                isLocalDeviceHost(finalUrl.host)
            ) {
                resp.close()
                throw IOException("Unsafe or non-HTTPS redirect")
            }
            if (resp.isRedirect) {
                val location = resp.header("Location")
                if (location != null) {
                    val resolved = finalUrl.resolve(location)
                    if (resolved == null ||
                        !resolved.isHttps ||
                        !EInkImageOptimizer.isSafeHttpsExternalUrl(resolved.toString()) ||
                        isLocalDeviceHost(resolved.host)
                    ) {
                        resp.close()
                        throw IOException("Unsafe or non-HTTPS redirect Location")
                    }
                }
            }
            resp
        }
    }
}

internal data class PluginHostThemeColors(
    val background: String,
    val surface: String,
    val surfaceContainer: String,
    val primary: String,
    val onPrimary: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val onSurface: String,
    val onSurfaceVariant: String,
    val outlineVariant: String,
    val error: String,
    val onErrorContainer: String
) {
    companion object {
        fun defaults(isDarkTheme: Boolean): PluginHostThemeColors = if (isDarkTheme) {
            PluginHostThemeColors(
                background = "#141218",
                surface = "#1d1b20",
                surfaceContainer = "#2b2930",
                primary = "#d0bcff",
                onPrimary = "#381e72",
                primaryContainer = "#4f378b",
                onPrimaryContainer = "#eaddff",
                onSurface = "#e6e0e9",
                onSurfaceVariant = "#cac4d0",
                outlineVariant = "#49454f",
                error = "#f2b8b5",
                onErrorContainer = "#f9dedc"
            )
        } else {
            PluginHostThemeColors(
                background = "#fef7ff",
                surface = "#ffffff",
                surfaceContainer = "#f3edf7",
                primary = "#6750a4",
                onPrimary = "#ffffff",
                primaryContainer = "#eaddff",
                onPrimaryContainer = "#21005d",
                onSurface = "#1d1b20",
                onSurfaceVariant = "#49454f",
                outlineVariant = "#cac4d0",
                error = "#b3261e",
                onErrorContainer = "#410e0b"
            )
        }

        fun fromColorScheme(colorScheme: ColorScheme): PluginHostThemeColors {
            fun Color.toHex(): String = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and toArgb())
            return PluginHostThemeColors(
                background = colorScheme.background.toHex(),
                surface = colorScheme.surface.toHex(),
                surfaceContainer = colorScheme.surfaceContainer.toHex(),
                primary = colorScheme.primary.toHex(),
                onPrimary = colorScheme.onPrimary.toHex(),
                primaryContainer = colorScheme.primaryContainer.toHex(),
                onPrimaryContainer = colorScheme.onPrimaryContainer.toHex(),
                onSurface = colorScheme.onSurface.toHex(),
                onSurfaceVariant = colorScheme.onSurfaceVariant.toHex(),
                outlineVariant = colorScheme.outlineVariant.toHex(),
                error = colorScheme.error.toHex(),
                onErrorContainer = colorScheme.onErrorContainer.toHex()
            )
        }
    }
}

internal fun buildPluginHostHtml(
    folderName: String,
    pluginJs: String,
    isDarkTheme: Boolean,
    startErrorPrefix: String,
    deviceStatus: CrossPointStatus? = null,
    themeColors: PluginHostThemeColors = PluginHostThemeColors.defaults(isDarkTheme)
): String {
    val safePluginJs = pluginJs
        .replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script")
        .replace(Regex("</style", RegexOption.IGNORE_CASE), "<\\/style")
    val quotedFolderName = JSONObject.quote(folderName).replace("</", "<\\/")
    val quotedStartErrPrefix = JSONObject.quote(startErrorPrefix).replace("</", "<\\/")
    val capabilitiesJson = JSONArray(deviceStatus?.pluginCapabilities ?: emptyList<String>()).toString()
    val pluginApiStr = deviceStatus?.pluginApi?.toString() ?: "null"
    val vectorFontsBool = deviceStatus?.vectorFonts == true

    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
<style>
  :root {
    --bg-color: ${themeColors.background};
    --surface-color: ${themeColors.surface};
    --surface-container: ${themeColors.surfaceContainer};
    --primary: ${themeColors.primary};
    --on-primary: ${themeColors.onPrimary};
    --primary-container: ${themeColors.primaryContainer};
    --on-primary-container: ${themeColors.onPrimaryContainer};
    --text: ${themeColors.onSurface};
    --text-variant: ${themeColors.onSurfaceVariant};
    --outline-variant: ${themeColors.outlineVariant};
    --error: ${themeColors.error};
    --on-error-container: ${themeColors.onErrorContainer};
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
  window.__cpPending = {};
  window.__cpSeq = 0;
  window.__cpResolve = function(callId, result) {
    var resolver = window.__cpPending[callId];
    if (resolver) {
      delete window.__cpPending[callId];
      resolver(result);
    }
  };
  function __cpInvoke(op, args) {
    return new Promise(function(resolve) {
      if (!window.AndroidBridge || !window.AndroidBridge.invokeAsync) {
        resolve(null);
        return;
      }
      var callId = "cp_" + (++window.__cpSeq) + "_" + Date.now();
      window.__cpPending[callId] = resolve;
      try {
        window.AndroidBridge.invokeAsync(callId, op, JSON.stringify(args || {}));
      } catch (e) {
        delete window.__cpPending[callId];
        resolve(JSON.stringify({ status: 500, error: e && e.message ? e.message : String(e) }));
      }
    });
  }

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
      var text = await __cpInvoke("readFile", { path: path });
      return text || null;
    },
    writeFile: async function(path, data) {
      if (!window.AndroidBridge) return false;
      var text = (data === null || data === undefined) ? "" : String(data);
      var bytes = new TextEncoder().encode(text);
      var bin = "";
      for (var i = 0; i < bytes.length; i += 8192) {
        bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
      }
      var b64 = window.btoa(bin);
      var res = await __cpInvoke("writeFile", { path: path, data: b64, isBase64: true });
      return res === "true";
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
      var res = await __cpInvoke("relay", { method: m, url: u, headersJson: JSON.stringify(h), body: b });
      try { return JSON.parse(res); } catch(e) { return { status: 500, error: e.message }; }
    },
    fetchToSd: async function(url, dest, headers) {
      if (!window.AndroidBridge) return { status: 500, error: "Bridge not available" };
      var res = await __cpInvoke("fetchToSd", { url: url, dest: dest, headersJson: JSON.stringify(headers || {}) });
      try { return JSON.parse(res); } catch(e) { return { status: 500, error: e.message }; }
    },
    crypto: async function(req) {
      if (!window.AndroidBridge) {
        return { error: "Crypto bridge not available" };
      }
      var payload = typeof req === "string" ? req : JSON.stringify(req || {});
      var res = await __cpInvoke("crypto", { requestJson: payload });
      try { return JSON.parse(res); } catch(e) { return { error: e.message || "Invalid JSON response" }; }
    },
    enqueueJob: async function(plugin, action, args) {
      if (!window.AndroidBridge) {
        return { error: "Job bridge not available" };
      }
      var argsStr = typeof args === "string" ? args : (args ? JSON.stringify(args) : "");
      var res = await __cpInvoke("enqueueJob", { pluginName: plugin || this.name, action: action || "", argsJson: argsStr });
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
          errDiv.style.color = 'var(--error)';
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

/**
 * Displays the sandboxed configuration screen for an installed SD plugin, supporting either
 * its `plugin.js` UI (via an isolated WebView + `ScopedPluginJsBridge`) or raw `config.json` editing.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PluginConfigSubpage(
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
    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { PluginHostThemeColors.fromColorScheme(colorScheme) }
    val hasPluginJs = !plugin.pluginJs.isNullOrBlank()
    val startErrorPrefix = resources.getString(R.string.dev_plugin_start_error)

    var showJsonEditor by remember(plugin.id) { mutableStateOf(!hasPluginJs) }
    var allowCryptoOverride by remember(plugin.id) { mutableStateOf(false) }
    var configText by remember(plugin.id, plugin.configJson) {
        mutableStateOf(plugin.configJson ?: "{\n  \n}")
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val bridgeScope = remember(plugin.folderName) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    DisposableEffect(plugin.folderName) {
        onDispose {
            bridgeScope.cancel()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val displayCapabilities = remember(plugin) { plugin.permissions }
        val showCryptoConsentToggle = hasPluginJs && !(plugin.hasDeclaredPermissions && "crypto" in plugin.permissions)

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
                        label = {
                            Text(
                                stringResource(R.string.dev_plugin_cap_allow_crypto),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
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

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
        ) {
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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    // Block any navigation away from the local plugin config HTML while AndroidBridge is attached
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUri = request?.url ?: return null
                                    val scheme = reqUri.scheme?.lowercase(Locale.ROOT).orEmpty()
                                    val host = reqUri.host?.lowercase(Locale.ROOT).orEmpty()
                                    if (scheme == "data" || scheme == "blob" || (scheme == "https" && host == "plugin.crosssend.local")) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    // Block direct external sub-resource requests; plugins must use AndroidBridge.relay()
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Forbidden",
                                        emptyMap(),
                                        ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                            }
                            val bridge = ScopedPluginJsBridge(
                                context = context,
                                resources = resources,
                                app = app,
                                plugin = plugin,
                                deviceBaseUrl = currentBaseUrl,
                                bridgeScope = bridgeScope,
                                webViewProvider = { webViewRef },
                                allowCryptoOverride = { allowCryptoOverride }
                            )
                            addJavascriptInterface(bridge, "AndroidBridge")

                            val html = buildPluginHostHtml(
                                folderName = plugin.folderName,
                                pluginJs = plugin.pluginJs.orEmpty(),
                                isDarkTheme = isDarkTheme,
                                startErrorPrefix = startErrorPrefix,
                                deviceStatus = deviceStatus,
                                themeColors = themeColors
                            )
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
                                label = { Text(stringResource(R.string.dev_config_json_file_label)) },
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
                                            configText = JSONObject(configText).toString(2)
                                        } catch (_: Exception) {
                                            try {
                                                configText = JSONArray(configText).toString(2)
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

/**
 * Displays the reader's built-in local Web UI inside a restricted WebView that confines
 * navigation and sub-resource fetches to the verified local CrossPoint device host.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DeviceWebUiSubpage(
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
                        private var cachedApNet: Network? = null
                        private var cachedApClient: OkHttpClient? = null
                        private val targetHost = try { webUiUrl.toUri().host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUri = request?.url ?: return true
                            val scheme = reqUri.scheme?.lowercase(Locale.ROOT).orEmpty()
                            val reqHost = reqUri.host?.lowercase(Locale.ROOT)?.removeSurrounding("[", "]")
                            val baseHost = targetHost?.removeSurrounding("[", "]")
                            val apNet = app.wifiManager.getApNetwork()
                            val allowed = (scheme == "http" || scheme == "https") &&
                                baseHost != null &&
                                reqHost == baseHost &&
                                isLocalDeviceHost(baseHost, apNet)
                            return !allowed
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
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
                            val scheme = request.url.scheme?.lowercase(Locale.ROOT).orEmpty()
                            if (scheme == "data" || scheme == "blob") {
                                return super.shouldInterceptRequest(view, request)
                            }
                            val apNet = app.wifiManager.getApNetwork()
                            val reqHost = request.url.host?.lowercase(Locale.ROOT)?.removeSurrounding("[", "]")
                            val baseHost = targetHost?.removeSurrounding("[", "]")
                            if ((scheme != "http" && scheme != "https") || baseHost == null || reqHost != baseHost || !isLocalDeviceHost(baseHost, apNet)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Forbidden",
                                    emptyMap(),
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            if (apNet != null && request.method.equals("GET", ignoreCase = true)) {
                                try {
                                    val client = cachedApClient?.takeIf { cachedApNet == apNet }
                                        ?: OkHttpClient.Builder()
                                            .followRedirects(false)
                                            .followSslRedirects(false)
                                            .socketFactory(apNet.socketFactory)
                                            .dns(object : Dns {
                                                override fun lookup(hostname: String): List<InetAddress> {
                                                    val addrs = try {
                                                        apNet.getAllByName(hostname).toList()
                                                    } catch (_: Exception) {
                                                        InetAddress.getAllByName(hostname).toList()
                                                    }
                                                    val localAddrs = addrs.filter { isAllowedLocalAddress(it) }
                                                    if (localAddrs.isEmpty()) {
                                                        throw UnknownHostException("Host is not a local device address: $hostname")
                                                    }
                                                    return localAddrs
                                                }
                                            })
                                            .connectTimeout(5, TimeUnit.SECONDS)
                                            .readTimeout(10, TimeUnit.SECONDS)
                                            .build().also {
                                                cachedApNet = apNet
                                                cachedApClient = it
                                            }

                                    val reqBuilder = Request.Builder()
                                        .url(request.url.toString())
                                        .get()

                                    request.requestHeaders?.forEach { (k, v) ->
                                        reqBuilder.addHeader(k, v)
                                    }

                                    val resp = client.newCall(reqBuilder.build()).execute()
                                    try {
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
                                                BoundedResponseInputStream(resp, MAX_WEB_UI_RESPONSE_BYTES)
                                            )
                                        } else {
                                            resp.close()
                                        }
                                    } catch (e: Exception) {
                                        resp.close()
                                        throw e
                                    }
                                } catch (_: Exception) {
                                    // Fall through to default handling
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
