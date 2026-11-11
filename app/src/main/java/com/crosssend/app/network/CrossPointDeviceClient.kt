package com.crosssend.app.network

import android.content.Context
import android.net.Network
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class CrossPointStatus(
    val ip: String,
    val wifiMode: String,
    val rssi: Int = 0,
    val firmwareVersion: String? = null,
    val freeHeap: Long = 0,
    val batteryPercent: Int? = null,
    val uptimeSeconds: Long = 0,
    val hardwareMac: String? = null,
    val deviceModel: String? = null,
    val customScreenWidth: Int? = null,
    val customScreenHeight: Int? = null,
    val firmwareFamily: String? = null,
    val pluginApi: Int? = null,
    val pluginCapabilities: List<String> = emptyList(),
    val vectorFonts: Boolean = false
) {
    val isX3: Boolean get() = (deviceModel ?: "").contains("X3", ignoreCase = true)
    val isA4: Boolean get() = (deviceModel ?: "").let { it.contains("A4", ignoreCase = true) || it.contains("EEGOREAD", ignoreCase = true) || it.contains("EEGO", ignoreCase = true) }
    val isM5: Boolean get() = (deviceModel ?: "").let { it.contains("M5", ignoreCase = true) || it.contains("PAPER", ignoreCase = true) }
    val isPicco: Boolean get() = (deviceModel ?: "").let { it.contains("PICCO", ignoreCase = true) || it.contains("PICO", ignoreCase = true) || it.contains("BOOX", ignoreCase = true) }
    val isMurphy: Boolean get() = (deviceModel ?: "").let { it.contains("MURPHY", ignoreCase = true) || it.contains("M4", ignoreCase = true) }
    val isReterminal: Boolean get() = (deviceModel ?: "").let { it.contains("RETERMINAL", ignoreCase = true) || it.contains("METALIO", ignoreCase = true) || it.contains("STICKY", ignoreCase = true) }
    val isX4: Boolean get() = !isX3 && !isA4 && !isM5 && !isPicco && !isReterminal && !isMurphy

    val screenWidth: Int get() = customScreenWidth ?: when {
        isX3 -> 528
        isA4 -> 552
        isM5 -> 540
        else -> 480 // X4, PICCO, RETERMINAL, MURPHY
    }

    val screenHeight: Int get() = customScreenHeight ?: when {
        isX3 -> 792
        isA4 -> 768
        isM5 -> 960
        else -> 800 // X4, PICCO, RETERMINAL, MURPHY
    }
}

typealias CrossPointDeviceStatus = CrossPointStatus

data class CrossPointPluginItem(
    val name: String,
    val title: String,
    val mount: String
)

data class CrossPointSettingItem(
    val key: String,
    val name: String,
    val category: String,
    val type: String, // "toggle", "enum", "value", "string", "bool", "int"
    val value: Any,
    val options: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null
)

class CrossPointDeviceClient(
    private val wifiManager: CrossPointWifiManager? = null,
    private val context: Context? = null
) {
    private val tag = "CrossPointDeviceClient"

    companion object {
        private const val MAX_CRYPTO_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_API_RESPONSE_BYTES = 2L * 1024 * 1024

        internal fun isSpecificDetectedModel(model: String?): Boolean {
            if (model.isNullOrBlank()) return false
            val trimmed = model.trim()
            return !trimmed.equals("CrossPoint Reader", ignoreCase = true) &&
                !trimmed.equals("crosspoint", ignoreCase = true)
        }

        fun saveDetectedModel(context: Context, model: String) {
            context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
                .edit {
                    putString("device_model", model)
                }
        }

        internal fun parseStatusJson(jsonStr: String, fallbackIp: String = ""): CrossPointStatus {
            val obj = JSONObject(jsonStr.ifBlank { "{}" })

            val fwVer = when {
                obj.has("version") && !obj.isNull("version") -> obj.optString("version")
                obj.has("firmware") && !obj.isNull("firmware") -> obj.optString("firmware")
                else -> null
            }
            val family = obj.optString("firmwareFamily", obj.optString("family", obj.optString("fork", ""))).trim()
            val effectiveFwVersion = if (family.isNotBlank()) {
                val familyPretty = when (family.lowercase()) {
                    "crossink" -> "CrossInk"
                    "crossmux" -> "CrossMux"
                    "papyrix" -> "PapyriX"
                    "snapix" -> "Snapix"
                    "inx" -> "INX"
                    "crossplay" -> "CrossPlay"
                    "picco", "boox picco" -> "Boox Picco"
                    "crosspoint" -> "CrossPoint"
                    else -> family.replaceFirstChar { it.uppercase() }
                }
                if (fwVer != null) {
                    if (fwVer.contains(familyPretty, ignoreCase = true) || fwVer.contains(family, ignoreCase = true)) {
                        fwVer
                    } else {
                        "$familyPretty $fwVer"
                    }
                } else {
                    familyPretty
                }
            } else {
                fwVer
            }

            val rawModel = obj.optString("device", obj.optString("board", obj.optString("model", obj.optString("hostname", "CrossPoint Reader")))).ifBlank { "CrossPoint Reader" }

            val widthOpt = when {
                obj.has("width") -> obj.optInt("width")
                obj.has("screenWidth") -> obj.optInt("screenWidth")
                obj.has("screen_width") -> obj.optInt("screen_width")
                else -> null
            }?.takeIf { it > 0 }

            val heightOpt = when {
                obj.has("height") -> obj.optInt("height")
                obj.has("screenHeight") -> obj.optInt("screenHeight")
                obj.has("screen_height") -> obj.optInt("screen_height")
                else -> null
            }?.takeIf { it > 0 }

            val profile = com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(rawModel)
            val detectedModel = if (profile != com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.X4) {
                profile.id
            } else {
                rawModel
            }

            val pluginApi = when {
                obj.has("pluginApi") && !obj.isNull("pluginApi") -> obj.optInt("pluginApi")
                obj.has("plugin_api") && !obj.isNull("plugin_api") -> obj.optInt("plugin_api")
                else -> null
            }

            val pluginCapabilities = parsePluginCapabilities(obj)
            val vectorFonts = when {
                obj.has("vectorFonts") && !obj.isNull("vectorFonts") -> obj.optBoolean("vectorFonts", false)
                obj.has("vector_fonts") && !obj.isNull("vector_fonts") -> obj.optBoolean("vector_fonts", false)
                else -> pluginCapabilities.any {
                    it.equals("vector-fonts", ignoreCase = true) || it.equals("vector_fonts", ignoreCase = true)
                }
            }

            return CrossPointStatus(
                ip = obj.optString("ip", fallbackIp),
                wifiMode = obj.optString("mode", obj.optString("wifi_mode", "AP")),
                rssi = obj.optInt("rssi", 0),
                firmwareVersion = effectiveFwVersion,
                freeHeap = if (obj.has("freeHeap")) obj.optLong("freeHeap") else obj.optLong("free_heap", 0),
                batteryPercent = if (obj.has("battery") && !obj.isNull("battery")) obj.optInt("battery") else null,
                uptimeSeconds = obj.optLong("uptime", 0),
                hardwareMac = if (obj.has("hardwareMac") && !obj.isNull("hardwareMac")) obj.optString("hardwareMac") else null,
                deviceModel = detectedModel,
                customScreenWidth = widthOpt,
                customScreenHeight = heightOpt,
                firmwareFamily = family.ifBlank { null },
                pluginApi = pluginApi,
                pluginCapabilities = pluginCapabilities,
                vectorFonts = vectorFonts
            )
        }

        private fun parsePluginCapabilities(obj: JSONObject): List<String> {
            val arr = obj.optJSONArray("pluginCapabilities") ?: obj.optJSONArray("plugin_capabilities")
            if (arr != null) {
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty()) list.add(item)
                }
                return list
            }
            val rawStr = when {
                obj.has("pluginCapabilities") && !obj.isNull("pluginCapabilities") -> obj.optString("pluginCapabilities", "")
                obj.has("plugin_capabilities") && !obj.isNull("plugin_capabilities") -> obj.optString("plugin_capabilities", "")
                else -> ""
            }
            if (rawStr.isBlank()) return emptyList()
            return rawStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedApNetwork: Network? = null
    @Volatile
    private var cachedApClient: OkHttpClient? = null

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

    private val client: OkHttpClient
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
                                    listOf(java.net.InetAddress.getByName(hostname))
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

    suspend fun fetchStatus(baseUrl: String): Result<CrossPointStatus> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/status"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val jsonStr = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                } ?: "{}"
                val fallbackIp = baseUrl.substringAfter("://").substringBefore('/')
                val status = parseStatusJson(jsonStr, fallbackIp)

                try {
                    val ctx = context ?: com.crosssend.app.CrossSendApp.instance
                    val modelStr = status.deviceModel
                    if (isSpecificDetectedModel(modelStr)) {
                        val profile = com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString(modelStr)
                        saveDetectedModel(ctx, profile.id)
                    }
                    if (status.customScreenWidth != null && status.customScreenHeight != null) {
                        ctx.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
                            .edit {
                                putInt("screen_width", status.customScreenWidth)
                                putInt("screen_height", status.customScreenHeight)
                            }
                    }
                } catch (_: Exception) {}

                Result.success(status)
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun fetchPlugins(baseUrl: String): Result<List<CrossPointPluginItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/plugins"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                } ?: "[]"
                val list = mutableListOf<CrossPointPluginItem>()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    list.add(
                        CrossPointPluginItem(
                            name = item.optString("name"),
                            title = item.optString("title", item.optString("name")),
                            mount = item.optString("mount", "settings")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun fetchSettings(
        baseUrl: String,
        keys: List<String> = emptyList()
    ): Result<List<CrossPointSettingItem>> = withContext(Dispatchers.IO) {
        try {
            val ctx = context ?: try { com.crosssend.app.CrossSendApp.instance } catch (_: Exception) { null }
            val defaultGeneralCategory = ctx?.getString(com.crosssend.app.R.string.dev_category_general) ?: "General"
            val defaultDeviceCategory = ctx?.getString(com.crosssend.app.R.string.dev_category_device) ?: "Device"
            val baseEndpoint = "${baseUrl.trimEnd('/')}/api/settings"
            val url = if (keys.isNotEmpty()) {
                val encodedKeys = keys.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
                "$baseEndpoint?keys=$encodedKeys"
            } else {
                baseEndpoint
            }
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val body = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                } ?: "[]"
                val list = mutableListOf<CrossPointSettingItem>()

                if (body.trim().startsWith("[")) {
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val key = item.optString("key")
                        val name = item.optString("name", key)
                        val category = item.optString("category", defaultGeneralCategory)
                        val type = item.optString("type", "string")
                        val value = item.opt("value") ?: ""
                        val optionsArr = item.optJSONArray("options")
                        val options = mutableListOf<String>()
                        if (optionsArr != null) {
                            for (j in 0 until optionsArr.length()) {
                                options.add(optionsArr.optString(j))
                            }
                        }
                        val minVal = if (item.has("min")) item.optDouble("min") else null
                        val maxVal = if (item.has("max")) item.optDouble("max") else null
                        val stepVal = if (item.has("step")) item.optDouble("step") else null
                        list.add(
                            CrossPointSettingItem(
                                key = key,
                                name = name,
                                category = category,
                                type = type,
                                value = value,
                                options = options,
                                min = minVal,
                                max = maxVal,
                                step = stepVal
                            )
                        )
                    }
                } else if (body.trim().startsWith("{")) {
                    val obj = JSONObject(body)
                    val objKeys = obj.keys()
                    while (objKeys.hasNext()) {
                        val key = objKeys.next()
                        val value = obj.get(key)
                        val type = when (value) {
                            is Boolean -> "bool"
                            is Int, is Long -> "int"
                            else -> "string"
                        }
                        list.add(
                            CrossPointSettingItem(
                                key = key,
                                name = key.replace("([A-Z])".toRegex(), " $1").trim().replaceFirstChar { it.uppercase() },
                                category = defaultDeviceCategory,
                                type = type,
                                value = value
                            )
                        )
                    }
                }

                Result.success(list)
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun updateSetting(baseUrl: String, key: String, value: Any): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/settings"
            val jsonPayload = JSONObject().apply {
                put(key, value)
            }.toString()

            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun uploadFont(baseUrl: String, familyName: String, fontFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!fontFile.exists() || !fontFile.isFile) {
                return@withContext Result.failure(IllegalArgumentException("Font file does not exist"))
            }
            val lowerName = fontFile.name.lowercase()
            if (!lowerName.endsWith(".cpfont") &&
                !lowerName.endsWith(".ttf") &&
                !lowerName.endsWith(".otf") &&
                !lowerName.endsWith(".ttc")
            ) {
                return@withContext Result.failure(IllegalArgumentException("Unsupported font format (expected .cpfont, .ttf, .otf, or .ttc)"))
            }
            val cleanFamily = familyName.trim().ifBlank { fontFile.nameWithoutExtension }
            val url = "${baseUrl.trimEnd('/')}/api/fonts/upload"
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("family", cleanFamily)
                .addFormDataPart("file", fontFile.name, fontFile.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val request = Request.Builder()
                .url(url)
                .post(multipartBody)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun executeCrypto(baseUrl: String, payloadJson: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
            if (payloadBytes.size > MAX_CRYPTO_PAYLOAD_BYTES) {
                return@withContext Result.failure(IllegalArgumentException("Crypto payload exceeds 64 KB UTF-8 limit"))
            }
            val url = "${baseUrl.trimEnd('/')}/api/crypto"
            val body = payloadBytes.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                }
                if (response.isSuccessful) {
                    Result.success(respBody ?: "{}")
                } else {
                    Result.failure(Exception("HTTP ${response.code}${if (!respBody.isNullOrBlank()) ": ${respBody.take(200)}" else ""}"))
                }
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun enqueuePluginJob(
        baseUrl: String,
        plugin: String,
        action: String,
        argsJson: JSONObject? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/plugin-jobs"
            val payload = JSONObject().apply {
                put("plugin", plugin)
                put("action", action)
                if (argsJson != null) {
                    put("args", argsJson)
                }
            }.toString()
            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                }
                if (response.isSuccessful) {
                    Result.success(respBody ?: "{}")
                } else {
                    Result.failure(Exception("HTTP ${response.code}${if (!respBody.isNullOrBlank()) ": ${respBody.take(200)}" else ""}"))
                }
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }

    suspend fun fetchPluginJobs(baseUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/plugin-jobs"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.let {
                    com.crosssend.app.converter.EInkImageOptimizer.readBoundedString(it, MAX_API_RESPONSE_BYTES)
                }
                if (response.isSuccessful) {
                    Result.success(respBody ?: "[]")
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            notifyIfUnreachable(e)
            Result.failure(e)
        }
    }
}
