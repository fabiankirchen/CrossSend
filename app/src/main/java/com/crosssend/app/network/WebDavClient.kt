package com.crosssend.app.network

import android.content.Context
import android.net.Network
import android.util.Xml
import androidx.core.content.edit
import com.crosssend.app.converter.EInkImageOptimizer
import com.crosssend.app.util.SafeLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WebDavClient(
    private val context: Context? = null,
    private val wifiManager: CrossPointWifiManager? = null,
    initialBaseUrl: String = "http://crosspoint.local"
) {
    private val tag = "WebDavClient"

    companion object {
        private const val WS_UPLOAD_PORT = 81
        private const val WS_CONNECT_TIMEOUT_MS = 3500L
        private const val WS_READY_TIMEOUT_MS = 8000L
        private const val WS_DONE_TIMEOUT_MS = 60_000L
        private const val WS_CHUNK_SIZE_BYTES = 16 * 1024
        private const val WS_MAX_QUEUE_BYTES = 512L * 1024L
        private const val MAX_SD_DOWNLOAD_BYTES = 100L * 1024 * 1024

        internal sealed class WsServerFrame {
            object Ready : WsServerFrame()
            data class Progress(val received: Long, val total: Long) : WsServerFrame()
            object Done : WsServerFrame()
            data class Error(val message: String) : WsServerFrame()
            data class Unknown(val raw: String) : WsServerFrame()
        }

        fun sanitizeRemotePath(rawPath: String): String {
            val normalized = rawPath
                .replace("\u0000", "")
                .replace('\\', '/')
            val segments = normalized.split('/')
                .filter { segment ->
                    val trimmed = segment.trim()
                    trimmed.isNotEmpty() && trimmed != "." && trimmed != ".."
                }
            return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
        }

        internal fun buildWebSocketUploadUrl(baseUrl: String): String? {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (!trimmed.contains("://")) "http://$trimmed" else trimmed
            return try {
                val uri = URI(withScheme)
                val wsScheme = when (uri.scheme?.lowercase()) {
                    "https" -> "wss"
                    "http" -> "ws"
                    else -> return null
                }
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                "$wsScheme://$host:$WS_UPLOAD_PORT/"
            } catch (_: Exception) {
                null
            }
        }

        internal fun buildHttpUploadUrl(baseUrl: String, remotePath: String): String {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val cleanPath = sanitizeRemotePath(remotePath)
            val parentDir = cleanPath.substringBeforeLast('/', "/")
            val targetDir = if (parentDir.isEmpty()) "/" else parentDir
            val encodedDir = URLEncoder.encode(targetDir, "UTF-8")
            return if (targetDir == "/") {
                "$cleanBase/upload?overwrite=1"
            } else {
                "$cleanBase/upload?dir=$encodedDir&path=$encodedDir&overwrite=1"
            }
        }

        internal fun formatWsStartFrame(fileName: String, fileSize: Long, dirPath: String): String {
            val normalizedDir = sanitizeRemotePath(dirPath)
            val safeName = fileName
                .replace("\u0000", "")
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("\r", "")
                .replace("\n", "")
            return "START:$safeName:$fileSize:$normalizedDir"
        }

        internal fun parseWsServerFrame(text: String): WsServerFrame {
            val trimmed = text.trim()
            return when {
                trimmed == "READY" -> WsServerFrame.Ready
                trimmed == "DONE" -> WsServerFrame.Done
                trimmed.startsWith("ERROR:") -> WsServerFrame.Error(trimmed.removePrefix("ERROR:").trim())
                trimmed.startsWith("PROGRESS:") -> {
                    val parts = trimmed.split(":")
                    val received = parts.getOrNull(1)?.toLongOrNull()
                    val total = parts.getOrNull(2)?.toLongOrNull()
                    if (received != null && total != null) {
                        WsServerFrame.Progress(received, total)
                    } else {
                        WsServerFrame.Unknown(trimmed)
                    }
                }
                else -> WsServerFrame.Unknown(trimmed)
            }
        }
    }

    @Volatile
    private var baseUrl: String = (
        context?.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            ?.getString("base_url", initialBaseUrl) ?: initialBaseUrl
        ).let { stored ->
            LocalDeviceHostValidator.normalizeLocalDeviceUrl(stored) ?: "http://crosspoint.local"
        }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(LocalDeviceHostValidator.createLocalDeviceCleartextGuardInterceptor())
        .build()

    @Volatile
    private var cachedApNetwork: Network? = null
    @Volatile
    private var cachedApClient: OkHttpClient? = null
    @Volatile
    private var cachedWsParentClient: OkHttpClient? = null
    @Volatile
    private var cachedWsClient: OkHttpClient? = null

    private fun resolveWifiManager(): CrossPointWifiManager? {
        return wifiManager ?: (context?.applicationContext as? com.crosssend.app.CrossSendApp)?.wifiManager
    }

    private fun notifyIfUnreachable(e: Exception) {
        if (e is ConnectException ||
            e is UnknownHostException ||
            e is NoRouteToHostException ||
            e is SocketTimeoutException
        ) {
            resolveWifiManager()?.reportDeviceUnreachable()
        }
    }

    val client: OkHttpClient
        get() {
            val apNet = resolveWifiManager()?.getApNetwork() ?: return baseClient
            val existing = cachedApClient
            if (existing != null && apNet === cachedApNetwork) {
                return existing
            }
            return synchronized(this) {
                val current = cachedApClient
                if (current != null && apNet === cachedApNetwork) {
                    current
                } else {
                    val newClient = baseClient.newBuilder()
                        .socketFactory(apNet.socketFactory)
                        .dns(object : okhttp3.Dns {
                            override fun lookup(hostname: String): List<java.net.InetAddress> {
                                return try {
                                    apNet.getAllByName(hostname).toList()
                                } catch (_: Exception) {
                                    val localAddrs = java.net.InetAddress.getAllByName(hostname).filter { addr ->
                                        LocalDeviceHostValidator.isAllowedLocalAddress(addr)
                                    }
                                    if (localAddrs.isEmpty()) {
                                        throw UnknownHostException("Blocked non-local fallback DNS resolution for AP host: $hostname")
                                    }
                                    localAddrs
                                }
                            }
                        })
                        .build()
                    cachedApNetwork = apNet
                    cachedApClient = newClient
                    newClient
                }
            }
        }

    private fun getWebSocketClient(): OkHttpClient {
        val parent = client
        val existing = cachedWsClient
        if (existing != null && parent === cachedWsParentClient) {
            return existing
        }
        return synchronized(this) {
            val current = cachedWsClient
            if (current != null && parent === cachedWsParentClient) {
                current
            } else {
                val newWsClient = parent.newBuilder()
                    .connectTimeout(WS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
                cachedWsParentClient = parent
                cachedWsClient = newWsClient
                newWsClient
            }
        }
    }

    fun updateBaseUrl(newBaseUrl: String): Boolean {
        val normalized = LocalDeviceHostValidator.normalizeLocalDeviceUrl(newBaseUrl)
        if (normalized == null) {
            SafeLogger.w(tag, "Rejected invalid or non-local WebDAV base URL")
            return false
        }
        this.baseUrl = normalized
        context?.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            ?.edit {
                putString("base_url", normalized)
            }
        return true
    }

    fun getBaseUrl(): String = baseUrl

    /**
     * Checks if the CrossPoint device is reachable.
     */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/status")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    resolveWifiManager()?.onTransferProgress()
                    return@withContext true
                }
            }
        } catch (_: Exception) {}

        try {
            val headRequest = Request.Builder().url(baseUrl).head().build()
            client.newCall(headRequest).execute().use { response ->
                val ok = response.isSuccessful || response.code in listOf(200, 207, 405)
                if (ok) {
                    resolveWifiManager()?.onTransferProgress()
                }
                ok
            }
        } catch (e: Exception) {
            SafeLogger.d(tag, "isReachable failed for $baseUrl: ${e.message}")
            notifyIfUnreachable(e)
            false
        }
    }

    /**
     * Ensures that a directory exists (via native /mkdir or WebDAV MKCOL).
     */
    suspend fun ensureDirectoryExists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)

        // 1. Try native CrossPoint /mkdir (POST form-urlencoded name=...&path=...)
        try {
            val parts = cleanPath.trim('/').split('/').filter { it.isNotBlank() }
            var current = ""
            for (part in parts) {
                val parent = if (current.isEmpty()) "/" else current
                val formBody = FormBody.Builder()
                    .add("name", part)
                    .add("path", parent)
                    .build()
                val request = Request.Builder().url("$baseUrl/mkdir").post(formBody).build()
                client.newCall(request).execute().close()
                resolveWifiManager()?.onTransferProgress()
                current += "/$part"
            }
            return@withContext true
        } catch (e: Exception) {
            SafeLogger.d(tag, "Native /mkdir failed, falling back to MKCOL: ${e.message}")
        }

        // 2. Fallback to WebDAV MKCOL
        val parts = cleanPath.trim('/').split('/').filter { it.isNotBlank() }
        var cur = ""
        for (part in parts) {
            cur += "/$part"
            val url = "$baseUrl$cur"
            val mkcolRequest = Request.Builder().url(url).method("MKCOL", null).build()
            try {
                client.newCall(mkcolRequest).execute().close()
                resolveWifiManager()?.onTransferProgress()
            } catch (e: Exception) {
                notifyIfUnreachable(e)
            }
        }
        true
    }

    suspend fun listDirectoryResult(remotePath: String): Result<List<RemoteDavItem>> = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)

        // 1. Try native CrossPoint REST API first (fast JSON, ~20ms on ESP32)
        var lastException: Exception? = null
        try {
            val encoded = if (cleanPath == "/") "" else "?path=" + URLEncoder.encode(cleanPath, "UTF-8")
            val apiUrl = "$baseUrl/api/files$encoded"
            val request = Request.Builder().url(apiUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.let { EInkImageOptimizer.readBoundedString(it, 5L * 1024 * 1024) }
                    if (!body.isNullOrBlank() && body.trim().startsWith("[")) {
                        val arr = JSONArray(body)
                        val items = mutableListOf<RemoteDavItem>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val name = obj.getString("name")
                            val isDir = obj.optBoolean("isDirectory", false)
                            val size = obj.optLong("size", 0L)
                            val itemPath = sanitizeRemotePath(if (cleanPath == "/") "/$name" else "$cleanPath/$name")
                            items.add(
                                RemoteDavItem(
                                    name = name,
                                    path = itemPath,
                                    isDirectory = isDir,
                                    sizeBytes = size,
                                    lastModified = null
                                )
                            )
                        }
                        resolveWifiManager()?.onTransferProgress()
                        SafeLogger.d(tag, "Native /api/files returned ${items.size} items")
                        return@withContext Result.success(items)
                    }
                }
            }
        } catch (e: Exception) {
            lastException = e
            SafeLogger.d(tag, "Native /api/files failed, falling back to WebDAV: ${e.message}")
        }

        // 2. Fallback to WebDAV PROPFIND
        val encodedPropPath = cleanPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val url = "$baseUrl$encodedPropPath"
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:resourcetype/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .header("Depth", "1")
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    SafeLogger.e(tag, "PROPFIND failed with HTTP ${response.code}")
                    return@withContext Result.failure(Exception("HTTP ${response.code} ($baseUrl)"))
                }

                val xmlBody = response.body?.let { EInkImageOptimizer.readBoundedString(it, 5L * 1024 * 1024) }
                resolveWifiManager()?.onTransferProgress()
                if (xmlBody == null) return@withContext Result.success(emptyList())
                Result.success(parsePropfindXml(xmlBody, cleanPath))
            }
        } catch (e: Exception) {
            SafeLogger.e(tag, "Error during PROPFIND", e)
            notifyIfUnreachable(e)
            lastException?.let { notifyIfUnreachable(it) }
            Result.failure(lastException ?: e)
        }
    }

    suspend fun listDirectory(remotePath: String): List<RemoteDavItem> {
        return listDirectoryResult(sanitizeRemotePath(remotePath)).getOrDefault(emptyList())
    }

    private suspend fun uploadFileViaWebSocket(
        localFile: File,
        cleanPath: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val wsUrl = buildWebSocketUploadUrl(baseUrl) ?: return false
        val parentDir = cleanPath.substringBeforeLast('/', "/")
        val targetDir = if (parentDir.isEmpty()) "/" else parentDir
        val targetFileName = cleanPath.substringAfterLast('/').ifBlank { localFile.name }
        val fileSize = localFile.length()

        val wsClient = getWebSocketClient()

        val openDeferred = CompletableDeferred<Boolean>()
        val readyDeferred = CompletableDeferred<Boolean>()
        val doneDeferred = CompletableDeferred<Boolean>()
        val wsFailed = AtomicBoolean(false)
        val wsSucceeded = AtomicBoolean(false)
        val wsClosed = AtomicBoolean(false)
        val wsErrorMsg = AtomicReference<String?>(null)

        val request = Request.Builder().url(wsUrl).build()
        val webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openDeferred.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = parseWsServerFrame(text)) {
                    is WsServerFrame.Ready -> {
                        resolveWifiManager()?.onTransferProgress()
                        readyDeferred.complete(true)
                    }
                    is WsServerFrame.Progress -> {
                        resolveWifiManager()?.onTransferProgress()
                        if (frame.total > 0) {
                            onProgress?.invoke((frame.received.toFloat() / frame.total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    is WsServerFrame.Done -> {
                        resolveWifiManager()?.onTransferProgress()
                        wsSucceeded.set(true)
                        onProgress?.invoke(1f)
                        doneDeferred.complete(true)
                        try {
                            webSocket.close(1000, "OK")
                        } catch (_: Exception) {}
                    }
                    is WsServerFrame.Error -> {
                        wsErrorMsg.set(frame.message)
                        wsFailed.set(true)
                        openDeferred.complete(false)
                        readyDeferred.complete(false)
                        doneDeferred.complete(false)
                        try {
                            webSocket.close(1002, frame.message.take(120))
                        } catch (_: Exception) {}
                    }
                    is WsServerFrame.Unknown -> {
                        SafeLogger.d(tag, "Ignoring unknown WS frame (length=${frame.raw.length})")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(code, reason)
                } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsClosed.set(true)
                openDeferred.complete(false)
                readyDeferred.complete(false)
                doneDeferred.complete(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsErrorMsg.set(t.message ?: "WebSocket failure")
                wsFailed.set(true)
                wsClosed.set(true)
                openDeferred.complete(false)
                readyDeferred.complete(false)
                doneDeferred.complete(false)
            }
        })

        try {
            val opened = withTimeoutOrNull(WS_CONNECT_TIMEOUT_MS) { openDeferred.await() } == true
            if (!opened || wsFailed.get() || wsClosed.get()) {
                SafeLogger.d(tag, "WS port 81 connect failed/timed out (${wsErrorMsg.get()}), falling back to HTTP /upload")
                webSocket.cancel()
                return false
            }

            val startFrame = formatWsStartFrame(targetFileName, fileSize, targetDir)
            if (!webSocket.send(startFrame)) {
                webSocket.cancel()
                return false
            }

            val ready = withTimeoutOrNull(WS_READY_TIMEOUT_MS) { readyDeferred.await() } == true
            if (!ready || wsFailed.get() || wsClosed.get()) {
                SafeLogger.d(tag, "WS READY timeout/error (${wsErrorMsg.get()}), falling back to HTTP /upload")
                webSocket.cancel()
                return false
            }

            val buffer = ByteArray(WS_CHUNK_SIZE_BYTES)
            localFile.inputStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (wsFailed.get() || wsClosed.get()) {
                        throw IOException(wsErrorMsg.get() ?: "WebSocket closed during upload")
                    }
                    while (webSocket.queueSize() > WS_MAX_QUEUE_BYTES) {
                        if (wsFailed.get() || wsClosed.get()) {
                            throw IOException(wsErrorMsg.get() ?: "WebSocket closed while draining queue")
                        }
                        delay(10)
                    }
                    if (!webSocket.send(buffer.toByteString(0, read))) {
                        throw IOException("WebSocket send returned false")
                    }
                    resolveWifiManager()?.onTransferProgress()
                }
            }

            val done = withTimeoutOrNull(WS_DONE_TIMEOUT_MS) { doneDeferred.await() } == true
            if (!done || !wsSucceeded.get()) {
                SafeLogger.d(tag, "WS DONE timeout/error (${wsErrorMsg.get()}), falling back to HTTP /upload")
                webSocket.cancel()
                return false
            }

            SafeLogger.d(tag, "WebSocket upload succeeded")
            return true
        } catch (_: Exception) {
            SafeLogger.d(tag, "WS upload failed, falling back to HTTP /upload")
            webSocket.cancel()
            return false
        }
    }

    /**
     * Uploads a local file to the CrossPoint device (v2.0 WebSocket port 81, native /upload, or WebDAV PUT).
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        val wifiMgr = resolveWifiManager()
        wifiMgr?.onTransferStarted()
        try {
            // 1. Try CrossPoint v2.0 WebSocket Fast Binary Upload (Port 81)
            if (uploadFileViaWebSocket(localFile, cleanPath, onProgress)) {
                wifiMgr?.onTransferProgress()
                return@withContext true
            }

            // 2. Fallback to native CrossPoint HTTP upload endpoint (/upload?dir=<targetDir>&overwrite=1)
            var nativeException: Exception? = null
            try {
                val uploadUrl = buildHttpUploadUrl(baseUrl, cleanPath)

                val targetFileName = cleanPath.substringAfterLast('/')
                val uploadFileName = if (targetFileName.isNotBlank()) targetFileName else localFile.name

                val fileBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = localFile.length()

                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(8192)
                        var uploaded = 0L
                        val total = localFile.length()
                        localFile.inputStream().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                                uploaded += read
                                wifiMgr?.onTransferProgress()
                                if (total > 0) {
                                    onProgress?.invoke(uploaded.toFloat() / total)
                                }
                            }
                        }
                    }
                }

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", uploadFileName, fileBody)
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        wifiMgr?.onTransferProgress()
                        SafeLogger.d(tag, "Native /upload succeeded")
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                nativeException = e
                SafeLogger.d(tag, "Native /upload failed, falling back to WebDAV PUT: ${e.message}")
            }

            // 3. Fallback to WebDAV PUT
            val encodedPath = cleanPath.split("/").joinToString("/") { segment ->
                if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val url = "$baseUrl$encodedPath"
            try {
                val mediaType = when {
                    localFile.name.endsWith(".epub", true) -> "application/epub+zip".toMediaType()
                    localFile.name.endsWith(".md", true) -> "text/markdown; charset=utf-8".toMediaType()
                    else -> "application/octet-stream".toMediaType()
                }

                val requestBody = object : RequestBody() {
                    override fun contentType() = mediaType
                    override fun contentLength() = localFile.length()

                    override fun writeTo(sink: BufferedSink) {
                        val buffer = ByteArray(8192)
                        var uploaded = 0L
                        val total = localFile.length()

                        localFile.inputStream().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                                uploaded += read
                                wifiMgr?.onTransferProgress()
                                if (total > 0) {
                                    onProgress?.invoke(uploaded.toFloat() / total)
                                }
                            }
                        }
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .put(requestBody)
                    .build()

                client.newCall(request).execute().use { response ->
                    val success = response.code in 200..299
                    if (success) {
                        wifiMgr?.onTransferProgress()
                    }
                    SafeLogger.d(tag, "WebDAV PUT returned ${response.code}, success: $success")
                    success
                }
            } catch (e: Exception) {
                SafeLogger.e(tag, "Failed to upload file via WebDAV PUT", e)
                notifyIfUnreachable(e)
                nativeException?.let { notifyIfUnreachable(it) }
                false
            }
        } finally {
            wifiMgr?.onTransferFinished()
        }
    }

    /**
     * Downloads a file from CrossPoint (native /download or WebDAV GET).
     */
    suspend fun downloadFile(
        remotePath: String,
        destinationFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)

        // 1. Try native CrossPoint download endpoint (/download?path=<cleanPath>)
        var nativeException: Exception? = null
        try {
            val encodedPath = URLEncoder.encode(cleanPath, "UTF-8")
            val downloadUrl = "$baseUrl/download?path=$encodedPath"
            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        val totalLength = body.contentLength()
                        if (totalLength > MAX_SD_DOWNLOAD_BYTES) {
                            throw IOException("Downloaded file exceeds 100 MB safety limit")
                        }
                        destinationFile.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            destinationFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var downloaded = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    downloaded += read
                                    if (downloaded > MAX_SD_DOWNLOAD_BYTES) {
                                        throw IOException("Downloaded file exceeds 100 MB safety limit")
                                    }
                                    output.write(buffer, 0, read)
                                    resolveWifiManager()?.onTransferProgress()
                                    if (totalLength > 0) {
                                        onProgress?.invoke(downloaded.toFloat() / totalLength)
                                    }
                                }
                            }
                        }
                        resolveWifiManager()?.onTransferProgress()
                        SafeLogger.d(tag, "Native /download succeeded")
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            try { destinationFile.delete() } catch (_: Exception) {}
            nativeException = e
            SafeLogger.d(tag, "Native /download failed, falling back to WebDAV GET: ${e.message}")
        }

        // 2. Fallback to WebDAV GET
        val encodedPathSegments = cleanPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val url = "$baseUrl$encodedPathSegments"
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SafeLogger.e(tag, "WebDAV GET failed with HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body ?: return@withContext false
                val totalLength = body.contentLength()
                if (totalLength > MAX_SD_DOWNLOAD_BYTES) {
                    throw IOException("Downloaded file exceeds 100 MB safety limit")
                }
                destinationFile.parentFile?.mkdirs()

                body.byteStream().use { input ->
                    destinationFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            downloaded += read
                            if (downloaded > MAX_SD_DOWNLOAD_BYTES) {
                                throw IOException("Downloaded file exceeds 100 MB safety limit")
                            }
                            output.write(buffer, 0, read)
                            resolveWifiManager()?.onTransferProgress()
                            if (totalLength > 0) {
                                onProgress?.invoke(downloaded.toFloat() / totalLength)
                            }
                        }
                    }
                }
                resolveWifiManager()?.onTransferProgress()
                SafeLogger.d(tag, "WebDAV GET download succeeded")
                true
            }
        } catch (e: Exception) {
            try { destinationFile.delete() } catch (_: Exception) {}
            SafeLogger.e(tag, "Failed to download file via WebDAV GET", e)
            notifyIfUnreachable(e)
            nativeException?.let { notifyIfUnreachable(it) }
            false
        }
    }

    /**
     * Deletes a file or directory (native /delete or WebDAV DELETE).
     */
    suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)

        // 1. Try native CrossPoint /delete endpoint (POST form urlencoded path=... & paths=["..."])
        var nativeException: Exception? = null
        try {
            val deleteUrl = "$baseUrl/delete"
            val formBody = FormBody.Builder()
                .add("path", cleanPath)
                .add("paths", JSONArray().put(cleanPath).toString())
                .build()
            val request = Request.Builder().url(deleteUrl).post(formBody).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    resolveWifiManager()?.onTransferProgress()
                    SafeLogger.d(tag, "Native /delete succeeded")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            nativeException = e
            SafeLogger.d(tag, "Native /delete failed, falling back to WebDAV DELETE: ${e.message}")
        }

        // 2. Fallback to WebDAV DELETE
        val encodedPathSegments = cleanPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val url = "$baseUrl$encodedPathSegments"
        try {
            val request = Request.Builder().url(url).delete().build()
            client.newCall(request).execute().use { response ->
                val success = response.code in 200..299 || response.code == 404
                if (success) {
                    resolveWifiManager()?.onTransferProgress()
                }
                SafeLogger.d(tag, "WebDAV DELETE returned ${response.code}, success: $success")
                success
            }
        } catch (e: Exception) {
            SafeLogger.e(tag, "Failed to delete remote file via WebDAV DELETE", e)
            notifyIfUnreachable(e)
            nativeException?.let { notifyIfUnreachable(it) }
            false
        }
    }

    /**
     * Renames a file on CrossPoint via native /rename endpoint.
     */
    suspend fun renameFile(remotePath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        val safeName = newName
            .replace("\u0000", "")
            .replace("/", "")
            .replace("\\", "")
            .trim()
        if (safeName.isEmpty() || safeName == "." || safeName == "..") return@withContext false
        try {
            val formBody = FormBody.Builder()
                .add("path", cleanPath)
                .add("name", safeName)
                .build()
            val request = Request.Builder().url("$baseUrl/rename").post(formBody).build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            false
        }
    }

    /**
     * Moves a file on CrossPoint via native /move endpoint.
     */
    suspend fun moveFile(remotePath: String, destFolder: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        val cleanDest = sanitizeRemotePath(destFolder)
        try {
            val formBody = FormBody.Builder()
                .add("path", cleanPath)
                .add("dest", cleanDest)
                .build()
            val request = Request.Builder().url("$baseUrl/move").post(formBody).build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            false
        }
    }

    /**
     * Reads a text/JSON file directly (native /download or GET).
     */
    suspend fun readTextFile(remotePath: String): String? = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        // 1. Try native download endpoint (/download?path=...)
        var nativeException: Exception? = null
        try {
            val encoded = URLEncoder.encode(cleanPath, "UTF-8")
            val url = "$baseUrl/download?path=$encoded"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val str = response.body?.let { EInkImageOptimizer.readBoundedString(it, 5L * 1024 * 1024) }
                    if (str != null) {
                        resolveWifiManager()?.onTransferProgress()
                        return@withContext str
                    }
                }
            }
        } catch (e: Exception) {
            nativeException = e
        }

        // 2. Fallback direct GET
        val encodedPathSegments = cleanPath.split("/").joinToString("/") { segment ->
            if (segment.isEmpty()) "" else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        try {
            val url = "$baseUrl$encodedPathSegments"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    resolveWifiManager()?.onTransferProgress()
                    response.body?.let { EInkImageOptimizer.readBoundedString(it, 5L * 1024 * 1024) }
                } else null
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            nativeException?.let { notifyIfUnreachable(it) }
            null
        }
    }

    /**
     * Writes a text/JSON string directly to CrossPoint.
     */
    suspend fun writeTextFile(remotePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        val parentDir = cleanPath.substringBeforeLast('/', "")
        if (parentDir.isNotBlank() && parentDir != "/") {
            ensureDirectoryExists(parentDir)
        }
        val temp = File.createTempFile("crosspoint_text_", ".tmp", context?.cacheDir)
        try {
            temp.writeText(content)
            uploadFile(temp, cleanPath)
        } finally {
            temp.delete()
        }
    }

    /**
     * Uploads raw binary data directly to CrossPoint.
     */
    suspend fun uploadBytes(remotePath: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val cleanPath = sanitizeRemotePath(remotePath)
        val parentDir = cleanPath.substringBeforeLast('/', "")
        if (parentDir.isNotBlank() && parentDir != "/") {
            ensureDirectoryExists(parentDir)
        }
        val temp = File.createTempFile("crosspoint_bin_", ".tmp", context?.cacheDir)
        try {
            temp.writeBytes(bytes)
            uploadFile(temp, cleanPath)
        } finally {
            temp.delete()
        }
    }

    private fun parsePropfindXml(xml: String, requestPath: String): List<RemoteDavItem> {
        val items = mutableListOf<RemoteDavItem>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentHref: String? = null
        var isDir = false
        var contentLength: Long = 0
        var lastModified: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.lowercase() ?: ""

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        tagName.endsWith("href") -> {
                            currentHref = parser.nextText().trim()
                        }
                        tagName.endsWith("collection") -> {
                            isDir = true
                        }
                        tagName.endsWith("getcontentlength") -> {
                            contentLength = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                        tagName.endsWith("getlastmodified") -> {
                            lastModified = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName.endsWith("response")) {
                        currentHref?.let { href ->
                            val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
                            val cleanHref = sanitizeRemotePath(decodedHref)

                            // Ignore the directory itself
                            val reqClean = sanitizeRemotePath(requestPath)
                            if (cleanHref != reqClean && cleanHref != "$reqClean/") {
                                val itemName = cleanHref.substringAfterLast('/').ifEmpty {
                                    cleanHref.trimEnd('/').substringAfterLast('/')
                                }
                                if (itemName.isNotBlank()) {
                                    items.add(
                                        RemoteDavItem(
                                            name = itemName,
                                            path = cleanHref,
                                            isDirectory = isDir,
                                            sizeBytes = contentLength,
                                            lastModified = lastModified
                                        )
                                    )
                                }
                            }
                        }
                        currentHref = null
                        isDir = false
                        contentLength = 0
                        lastModified = null
                    }
                }
            }
            eventType = parser.next()
        }

        return items
    }
}
