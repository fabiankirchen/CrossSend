package com.crosssend.app.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import androidx.core.content.edit
import com.crosssend.app.CrossSendApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class WifiConnectionState {
    object Disconnected : WifiConnectionState()
    object SearchingOnLan : WifiConnectionState()
    object ConnectingToAp : WifiConnectionState()
    data class ConnectedToCrossPointAp(val ip: String) : WifiConnectionState()
    data class ConnectedOnLocalNetwork(val ip: String) : WifiConnectionState()
    data class Error(val message: String) : WifiConnectionState()
}

class CrossPointWifiManager(
    private val context: Context
) {
    private val tag = "CrossPointWifiManager"
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private val watchdogLock = Any()

    private val internetNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val wifiNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()

    @Volatile
    private var lastSuccessfulContactMs: Long = 0L
    @Volatile
    private var lastResumeDiscoveryMs: Long = 0L
    @Volatile
    private var resumeProbeJob: Job? = null
    private val unreachableProbeInFlight = AtomicBoolean(false)

    private val probeBaseClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(WATCHDOG_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(WATCHDOG_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var cachedApProbeNetwork: Network? = null
    @Volatile
    private var cachedApProbeClient: okhttp3.OkHttpClient? = null

    private val internetMonitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (network == boundNetwork) return
            try {
                connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                    internetNetworks[network] = caps
                }
            } catch (_: SecurityException) {}
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (network == boundNetwork) return
            internetNetworks[network] = networkCapabilities
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            internetNetworks.remove(network)
        }
    }

    private val lanMonitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (network == boundNetwork) return
            try {
                connectivityManager.getNetworkCapabilities(network)?.let { caps ->
                    wifiNetworks[network] = caps
                }
            } catch (_: SecurityException) {}
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (network == boundNetwork) return
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiNetworks[network] = networkCapabilities
            } else {
                wifiNetworks.remove(network)
                handleWifiTransportLossIfNeeded()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            wifiNetworks.remove(network)
            if (network != boundNetwork) {
                handleWifiTransportLossIfNeeded()
            }
        }
    }

    private val _connectionState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Disconnected)
    val connectionState: StateFlow<WifiConnectionState> = _connectionState.asStateFlow()

    init {
        try {
            val internetRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            connectivityManager.registerNetworkCallback(internetRequest, internetMonitorCallback)
        } catch (e: SecurityException) {
            Log.w(tag, "Unable to register internet monitor callback: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Internet monitor registration error: ${e.message}")
        }

        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(wifiRequest, lanMonitorCallback)
        } catch (e: SecurityException) {
            Log.w(tag, "Unable to register LAN Wi-Fi monitor callback: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "LAN Wi-Fi monitor registration error: ${e.message}")
        }
    }

    /**
     * Checks whether local network access is permitted (runtime permission on Android 17 / API 37+).
     */
    fun hasLocalNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return context.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    }

    fun getApSsid(): String {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getString("wifi_ssid", "Cross") ?: "Cross"
    }

    fun setApSsid(ssid: String) {
        val clean = ssid.trim()
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putString("wifi_ssid", clean)
            }
    }

    fun getLanBaseUrl(): String {
        return context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getString("lan_base_url", "http://crosspoint.local") ?: "http://crosspoint.local"
    }

    fun setLanBaseUrl(url: String) {
        val clean = url.trim()
        context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .edit {
                putString("lan_base_url", clean)
            }
    }

    /**
     * Connects specifically to the CrossPoint/CrossPlay Access Point (supporting forks and custom SSIDs).
     * Uses Android's WifiNetworkSpecifier without requiring Internet validation!
     */
    fun connectToCrossPointAp(
        ssid: String = getApSsid(),
        defaultIp: String = "192.168.4.1"
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _connectionState.value = WifiConnectionState.Error(
                context.getString(com.crosssend.app.R.string.wifi_error_android_10)
            )
            return
        }
        if (!hasLocalNetworkPermission()) {
            _connectionState.value = WifiConnectionState.Error(
                context.getString(com.crosssend.app.R.string.wifi_error_local_network_permission)
            )
            return
        }

        // Release any existing connection first
        disconnectFromAp()

        val savedModel = context.getSharedPreferences("crosspoint_prefs", Context.MODE_PRIVATE)
            .getString("device_model", null)
        val candidatePrefix = when {
            ssid != "Cross" && ssid.isNotBlank() -> ssid
            savedModel?.contains("PICCO", ignoreCase = true) == true || savedModel?.contains("PICO", ignoreCase = true) == true -> "Picco"
            savedModel?.contains("BOOX", ignoreCase = true) == true -> "BOOX"
            savedModel?.contains("EEGOREAD", ignoreCase = true) == true -> "EEGOREAD"
            savedModel?.contains("MURPHY", ignoreCase = true) == true || savedModel?.contains("M4", ignoreCase = true) == true -> "Murphy"
            savedModel?.contains("CROSSINK", ignoreCase = true) == true -> "CrossInk"
            savedModel?.contains("PAPYRIX", ignoreCase = true) == true -> "PapyriX"
            savedModel?.contains("CROSSMUX", ignoreCase = true) == true -> "CrossMux"
            savedModel?.contains("SNAPIX", ignoreCase = true) == true -> "Snapix"
            savedModel?.contains("INX", ignoreCase = true) == true -> "INX"
            savedModel?.contains("XTEINK", ignoreCase = true) == true || savedModel?.contains("X3", ignoreCase = true) == true || savedModel?.contains("X4", ignoreCase = true) == true -> "Xteink"
            else -> "Cross"
        }
        val effectiveSsid = candidatePrefix.trim().ifBlank { "Cross" }
        _connectionState.value = WifiConnectionState.ConnectingToAp
        Log.d(tag, "Requesting connection to Wi-Fi SSID pattern: $effectiveSsid")

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher(effectiveSsid, PatternMatcher.PATTERN_PREFIX))
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // Remove INTERNET capability so Android knows it is an IoT device without WAN
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(tag, "Connected to CrossPoint AP! Network: $network")
                    boundNetwork = network

                    // Explicitly unbind process so mobile data/WAN stays available for external internet
                    // (Adobe/ContentReserve relay, plugin downloads, article parsing),
                    // while WebDavClient and DeviceClient route explicitly via boundNetwork.socketFactory.
                    try {
                        connectivityManager.bindProcessToNetwork(null)
                    } catch (e: Exception) {
                        Log.w(tag, "bindProcessToNetwork(null) notice: ${e.message}")
                    }

                    try {
                        CrossSendApp.instance.webDavClient.updateBaseUrl("http://$defaultIp")
                    } catch (_: Exception) {}

                    _connectionState.value = WifiConnectionState.ConnectedToCrossPointAp(defaultIp)
                    startReachabilityWatchdog()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(tag, "CrossPoint AP network lost.")
                    stopReachabilityWatchdog()
                    unbindProcess()
                    _connectionState.value = WifiConnectionState.Disconnected
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.w(tag, "CrossPoint AP connection was cancelled or unavailable.")
                    stopReachabilityWatchdog()
                    unbindProcess()
                    _connectionState.value = WifiConnectionState.Disconnected
                }
            }

            activeCallback = callback
            connectivityManager.requestNetwork(request, callback, 15_000)

        } catch (e: Exception) {
            Log.e(tag, "Failed to request CrossPoint AP network", e)
            stopReachabilityWatchdog()
            _connectionState.value = WifiConnectionState.Error(e.message ?: context.getString(com.crosssend.app.R.string.wifi_error_connection))
        }
    }

    /**
     * Cancels any active connection attempt (Hotspot or LAN discovery) and resets to Disconnected.
     */
    fun cancelConnectionAttempt() {
        disconnectFromAp()
    }

    /**
     * Disconnects from the CrossPoint AP and restores normal network routing.
     */
    fun disconnectFromAp() {
        stopReachabilityWatchdog()
        activeCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(tag, "Error unregistering network callback: ${e.message}")
            }
            activeCallback = null
        }
        unbindProcess()
        _connectionState.value = WifiConnectionState.Disconnected
    }

    private fun unbindProcess() {
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        boundNetwork = null
        cachedApProbeNetwork = null
        cachedApProbeClient = null
    }

    /**
     * Returns the dedicated Network object for the reader AP if currently connected.
     */
    fun getApNetwork(): Network? = boundNetwork

    /**
     * Finds an active network with verified INTERNET capability (e.g. Mobile Data or WAN Wi-Fi).
     * Used to route relay calls and external downloads while connected to the offline reader AP.
     */
    fun getInternetNetwork(): Network? {
        val cm = connectivityManager
        // 1. Check active default network first if it's not the AP network
        val active = cm.activeNetwork
        if (active != null && active != boundNetwork) {
            val caps = cm.getNetworkCapabilities(active) ?: internetNetworks[active]
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            ) {
                return active
            }
        }

        // 2. Scan tracked internet-capable networks (avoids deprecated ConnectivityManager.allNetworks)
        var fallbackInternet: Network? = null
        for ((net, cachedCaps) in internetNetworks) {
            if (net == boundNetwork) continue
            val caps = cm.getNetworkCapabilities(net) ?: cachedCaps
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            ) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return net
                }
                if (fallbackInternet == null) {
                    fallbackInternet = net
                }
            }
        }
        return fallbackInternet
    }

    /**
     * Creates an OkHttpClient.Builder explicitly bound to the phone's internet network (Cellular/WAN),
     * completely bypassing the CrossPoint AP captive portal, with DNS-level SSRF protection
     * and strict HTTPS enforcement for external internet traffic.
     */
    fun getInternetOkHttpClientBuilder(timeoutSeconds: Long = 15): okhttp3.OkHttpClient.Builder {
        val builder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(com.crosssend.app.converter.EInkImageOptimizer.httpsOnlyInterceptor)
            .addNetworkInterceptor(com.crosssend.app.converter.EInkImageOptimizer.httpsOnlyInterceptor)
        val internetNet = getInternetNetwork()
        if (internetNet != null) {
            builder.socketFactory(internetNet.socketFactory)
            builder.dns(com.crosssend.app.converter.EInkImageOptimizer.wrapWithSafeDns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return internetNet.getAllByName(hostname).toList()
                }
            }))
        } else {
            builder.dns(com.crosssend.app.converter.EInkImageOptimizer.ssrfSafeDns)
        }
        return builder
    }

    /**
     * Mark as connected via existing local network (e.g. Home Wi-Fi or Phone Hotspot)
     * If not on the hotspot subnet (192.168.4.1), also preserves the LAN URL.
     */
    fun setConnectedOnLan(ipOrHost: String) {
        if (!ipOrHost.contains("192.168.4.1")) {
            setLanBaseUrl(ipOrHost)
        }
        _connectionState.value = WifiConnectionState.ConnectedOnLocalNetwork(ipOrHost)
        startReachabilityWatchdog()
    }

    private fun startReachabilityWatchdog() {
        synchronized(watchdogLock) {
            watchdogJob?.cancel()
            lastSuccessfulContactMs = System.currentTimeMillis()
            watchdogJob = scope.launch {
                var consecutiveFailures = 0
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    val state = _connectionState.value
                    val (targetUrl, apNet) = when (state) {
                        is WifiConnectionState.ConnectedToCrossPointAp -> "http://${state.ip}" to boundNetwork
                        is WifiConnectionState.ConnectedOnLocalNetwork -> normalizeHttpUrl(state.ip) to null
                        else -> break
                    }

                    if (state is WifiConnectionState.ConnectedToCrossPointAp && apNet == null) {
                        transitionToDisconnected(state)
                        break
                    }

                    val reachable = probeStatusEndpoint(targetUrl, apNet, WATCHDOG_PROBE_TIMEOUT_MS)
                    if (reachable) {
                        consecutiveFailures = 0
                        lastSuccessfulContactMs = System.currentTimeMillis()
                    } else {
                        consecutiveFailures++
                        Log.d(tag, "Watchdog probe failed ($consecutiveFailures/2) for $targetUrl")
                        if (consecutiveFailures >= 2) {
                            Log.w(tag, "Watchdog lost reachability to $targetUrl; transitioning to Disconnected")
                            transitionToDisconnected(state)
                            break
                        }
                    }
                }
            }
        }
    }

    private fun startReachabilityWatchdogIfIdle() {
        synchronized(watchdogLock) {
            if (watchdogJob?.isActive != true && isConnected()) {
                startReachabilityWatchdog()
            }
        }
    }

    private fun stopReachabilityWatchdog() {
        synchronized(watchdogLock) {
            watchdogJob?.cancel()
            watchdogJob = null
        }
    }

    private fun handleWifiTransportLossIfNeeded() {
        val state = _connectionState.value
        if (state !is WifiConnectionState.ConnectedOnLocalNetwork) return
        if (wifiNetworks.isNotEmpty()) return

        val activeCaps = try {
            connectivityManager.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        } catch (_: Exception) {
            null
        }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return

        if (state.ip.contains("192.168.4.") && isPhoneOnHotspotSubnet()) {
            reportDeviceUnreachable()
            return
        }

        Log.d(tag, "Wi-Fi transport lost; transitioning ConnectedOnLocalNetwork -> Disconnected")
        stopReachabilityWatchdog()
        _connectionState.value = WifiConnectionState.Disconnected
    }

    private fun transitionToDisconnected(expectedState: WifiConnectionState? = null) {
        val current = _connectionState.value
        if (expectedState != null && current != expectedState) return
        if (current is WifiConnectionState.ConnectedToCrossPointAp) {
            disconnectFromAp()
        } else {
            stopReachabilityWatchdog()
            _connectionState.value = WifiConnectionState.Disconnected
        }
    }

    /**
     * Called by WebDavClient / CrossPointDeviceClient when a request fails with a connectivity exception
     * (ConnectException, UnknownHostException, NoRouteToHostException, SocketTimeoutException).
     * Verifies device reachability asynchronously and transitions stale connections to Disconnected.
     */
    fun reportDeviceUnreachable() {
        val state = _connectionState.value
        if (state !is WifiConnectionState.ConnectedOnLocalNetwork &&
            state !is WifiConnectionState.ConnectedToCrossPointAp
        ) {
            return
        }
        if (!unreachableProbeInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                val current = _connectionState.value
                val (targetUrl, apNet) = when (current) {
                    is WifiConnectionState.ConnectedToCrossPointAp -> "http://${current.ip}" to boundNetwork
                    is WifiConnectionState.ConnectedOnLocalNetwork -> normalizeHttpUrl(current.ip) to null
                    else -> return@launch
                }
                if (current is WifiConnectionState.ConnectedToCrossPointAp && apNet == null) {
                    transitionToDisconnected(current)
                    return@launch
                }
                val reachable = probeStatusEndpoint(targetUrl, apNet, WATCHDOG_PROBE_TIMEOUT_MS)
                if (reachable) {
                    lastSuccessfulContactMs = System.currentTimeMillis()
                } else {
                    val staleMs = System.currentTimeMillis() - lastSuccessfulContactMs
                    Log.w(tag, "Device unreachable at $targetUrl (stale ${staleMs}ms); transitioning to Disconnected")
                    transitionToDisconnected(current)
                }
            } finally {
                unreachableProbeInFlight.set(false)
            }
        }
    }

    /**
     * Verifies or refreshes CrossPoint connection status when the app returns to foreground.
     */
    fun verifyConnectionOnResume() {
        val state = _connectionState.value
        if (isConnected()) {
            if (resumeProbeJob?.isActive == true) return
            resumeProbeJob = scope.launch {
                val current = _connectionState.value
                val (targetUrl, apNet) = when (current) {
                    is WifiConnectionState.ConnectedToCrossPointAp -> "http://${current.ip}" to boundNetwork
                    is WifiConnectionState.ConnectedOnLocalNetwork -> normalizeHttpUrl(current.ip) to null
                    else -> return@launch
                }
                if (current is WifiConnectionState.ConnectedToCrossPointAp && apNet == null) {
                    transitionToDisconnected(current)
                    return@launch
                }
                val reachable = probeStatusEndpoint(targetUrl, apNet, WATCHDOG_PROBE_TIMEOUT_MS)
                if (reachable) {
                    lastSuccessfulContactMs = System.currentTimeMillis()
                    startReachabilityWatchdogIfIdle()
                } else {
                    Log.d(tag, "On-resume probe failed for $targetUrl; transitioning to Disconnected")
                    transitionToDisconnected(current)
                }
            }
            return
        }

        if (state !is WifiConnectionState.Disconnected) return
        if (!hasLocalNetworkPermission()) return

        val now = System.currentTimeMillis()
        if (now - lastResumeDiscoveryMs < RESUME_DISCOVERY_THROTTLE_MS) return
        if (resumeProbeJob?.isActive == true) return
        lastResumeDiscoveryMs = now

        resumeProbeJob = scope.launch {
            val lanUrl = normalizeHttpUrl(getLanBaseUrl())
            val directMatch = if (!lanUrl.contains("192.168.4.1") || isPhoneOnHotspotSubnet()) {
                if (probeStatusEndpoint(lanUrl, null, RESUME_LAN_PROBE_TIMEOUT_MS)) lanUrl else null
            } else {
                null
            }

            val detected = directMatch ?: autoDetectCrossPoint(preferredUrl = lanUrl)
            if (detected != null) {
                try {
                    CrossSendApp.instance.webDavClient.updateBaseUrl(detected)
                } catch (_: Exception) {}
                if (_connectionState.value is WifiConnectionState.Disconnected) {
                    setConnectedOnLan(detected)
                }
            }
        }
    }

    private fun normalizeHttpUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun getProbeClient(apNetwork: Network?, timeoutMs: Long): okhttp3.OkHttpClient {
        if (apNetwork == null && timeoutMs == WATCHDOG_PROBE_TIMEOUT_MS) {
            return probeBaseClient
        }
        if (apNetwork != null && timeoutMs == WATCHDOG_PROBE_TIMEOUT_MS) {
            val existing = cachedApProbeClient
            if (existing != null && apNetwork === cachedApProbeNetwork) {
                return existing
            }
            return synchronized(this) {
                val current = cachedApProbeClient
                if (current != null && apNetwork === cachedApProbeNetwork) {
                    current
                } else {
                    val built = probeBaseClient.newBuilder()
                        .socketFactory(apNetwork.socketFactory)
                        .dns(object : okhttp3.Dns {
                            override fun lookup(hostname: String): List<InetAddress> {
                                return try {
                                    apNetwork.getAllByName(hostname).toList()
                                } catch (_: Exception) {
                                    listOf(InetAddress.getByName(hostname))
                                }
                            }
                        })
                        .build()
                    cachedApProbeNetwork = apNetwork
                    cachedApProbeClient = built
                    built
                }
            }
        }
        val base = if (apNetwork != null) getProbeClient(apNetwork, WATCHDOG_PROBE_TIMEOUT_MS) else probeBaseClient
        return base.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun probeStatusEndpoint(baseUrl: String, apNetwork: Network?, timeoutMs: Long): Boolean {
        val cleanBase = normalizeHttpUrl(baseUrl)
        if (cleanBase == "http://" || cleanBase == "https://") return false
        return try {
            val req = okhttp3.Request.Builder()
                .url("$cleanBase/api/status")
                .get()
                .build()
            getProbeClient(apNetwork, timeoutMs).newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPhoneOnHotspotSubnet(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
            iface.isUp && !iface.isLoopback && iface.inetAddresses.toList().any { addr ->
                addr is Inet4Address && addr.hostAddress?.startsWith("192.168.4.") == true
            }
        } == true
    } catch (_: Exception) {
        false
    }

    /**
     * Probes candidate IPs and uses UDP discovery (port 8134) in parallel to auto-detect CrossPoint.
     * Conforms to official crosspoint-reader discovery spec:
     * Broadcast 'hello' on UDP 8134 -> device replies 'crosspoint (on <hostname>);81'
     */
    suspend fun autoDetectCrossPoint(
        preferredUrl: String? = null,
        candidateIps: List<String> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        if (!hasLocalNetworkPermission()) {
            Log.w(tag, "Local network permission not granted on API 37+; skipping LAN discovery.")
            return@withContext null
        }

        val initialCandidates = (listOfNotNull(
            preferredUrl,
            getLanBaseUrl(),
            "http://crosspoint.local",
            "http://picco.local",
            "http://crossink.local",
            "http://papyrix.local",
            "http://crossmux.local",
            "http://inx.local",
            "http://snapix.local",
            "http://crossplay.local",
            "http://xteink.local",
            "http://boox.local",
            "http://eegoread.local",
            "http://murphy.local"
        ) + candidateIps)
            .distinct()
            .filter { !it.contains("192.168.4.1") }
            .toMutableList()

        if (isPhoneOnHotspotSubnet()) {
            initialCandidates.add("http://192.168.4.1")
        }
        val candidates = initialCandidates.distinct()

        val detectedUrl = withTimeoutOrNull(1600L) {
            coroutineScope {
                val foundDeferred = CompletableDeferred<String>()

                val boundNet = boundNetwork
                val apProbeClient = getProbeClient(boundNet, 1200L)
                val lanProbeClient = getProbeClient(null, 1200L)

                val httpJobs = candidates.map { cand ->
                    async(Dispatchers.IO) {
                        try {
                            val isApCandidate = cand.contains("192.168.4.1")
                            val probeClient = if (isApCandidate) apProbeClient else lanProbeClient
                            val url = if (cand.startsWith("http")) "$cand/api/status" else "http://$cand/api/status"
                            val req = okhttp3.Request.Builder().url(url).get().build()
                            probeClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val finalUrl = if (cand.startsWith("http")) cand else "http://$cand"
                                    foundDeferred.complete(finalUrl)
                                    return@async finalUrl
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.w(tag, "LAN HTTP probe blocked by SecurityException for $cand: ${e.message}")
                        } catch (_: Exception) {}
                        null
                    }
                }

                val udpJob = async(Dispatchers.IO) {
                    var multicastLock: WifiManager.MulticastLock? = null
                    try {
                        multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                            ?.createMulticastLock("crosssend_udp")
                            ?.apply {
                                setReferenceCounted(false)
                                acquire()
                            }

                        DatagramSocket().use { socket ->
                            socket.broadcast = true
                            socket.soTimeout = 1400
                            val data = "hello".toByteArray(Charsets.UTF_8)

                            val broadcastTargets = mutableSetOf<InetAddress>()
                            try {
                                broadcastTargets.add(InetAddress.getByName("255.255.255.255"))
                            } catch (_: Exception) {}

                            try {
                                val subnets = NetworkInterface.getNetworkInterfaces()?.toList()
                                    ?.flatMap { it.interfaceAddresses }
                                    ?.mapNotNull { it.broadcast }
                                if (subnets != null) {
                                    broadcastTargets.addAll(subnets)
                                }
                            } catch (_: Exception) {}

                            for (target in broadcastTargets) {
                                try {
                                    val packet = DatagramPacket(data, data.size, target, 8134)
                                    socket.send(packet)
                                } catch (_: Exception) {}
                            }

                            val buf = ByteArray(256)
                            val recvPacket = DatagramPacket(buf, buf.size)
                            socket.receive(recvPacket)
                            val reply = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8).trim()
                            val replyLower = reply.lowercase()
                            val isKnownDevice = listOf(
                                "crosspoint", "crossplay", "crossink", "crossmux", "papyrix", "snapix", "inx",
                                "xteink", "picco", "pico", "boox", "eegoread", "m5paper", "reterminal", "murphy"
                            ).any { replyLower.startsWith(it) }
                            if (isKnownDevice) {
                                val portPart = if (reply.contains(';')) reply.substringAfterLast(';').trim() else ""
                                val portCandidate = portPart.toIntOrNull()
                                val host = recvPacket.address.hostAddress ?: ""
                                if (host.isNotBlank()) {
                                    val port80Url = "http://$host"
                                    var detectedIp: String? = null

                                    try {
                                        val checkReq = okhttp3.Request.Builder().url("$port80Url/api/status").get().build()
                                        lanProbeClient.newCall(checkReq).execute().use { checkResp ->
                                            if (checkResp.isSuccessful) {
                                                detectedIp = port80Url
                                            }
                                        }
                                    } catch (_: Exception) {}

                                    if (detectedIp == null && portCandidate != null && portCandidate != 80) {
                                        val candidateUrl = "http://$host:$portCandidate"
                                        try {
                                            val checkReq = okhttp3.Request.Builder().url("$candidateUrl/api/status").get().build()
                                            lanProbeClient.newCall(checkReq).execute().use { checkResp ->
                                                if (checkResp.isSuccessful) {
                                                    detectedIp = candidateUrl
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }

                                    val finalUrl = detectedIp ?: if (portCandidate != null && portCandidate != 80 && portCandidate != 81) {
                                        "http://$host:$portCandidate"
                                    } else {
                                        port80Url
                                    }

                                    foundDeferred.complete(finalUrl)
                                    return@async finalUrl
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.w(tag, "UDP discovery blocked by SecurityException: ${e.message}")
                    } catch (_: Exception) {
                    } finally {
                        try {
                            if (multicastLock?.isHeld == true) {
                                multicastLock.release()
                            }
                        } catch (_: Exception) {}
                    }
                    null
                }

                val allDeferreds = httpJobs + udpJob

                select<String?> {
                    foundDeferred.onAwait { found ->
                        allDeferreds.forEach { it.cancel() }
                        found
                    }
                    async {
                        val results = allDeferreds.awaitAll()
                        results.firstOrNull { it != null }
                    }.onAwait { it }
                }
            }
        }

        if (detectedUrl != null) {
            setConnectedOnLan(detectedUrl)
            Log.d(tag, "Auto-detected CrossPoint/CrossPlay at $detectedUrl")
        }

        detectedUrl
    }

    suspend fun connectOrDiscover(webDavClient: WebDavClient): Boolean {
        if (_connectionState.value is WifiConnectionState.ConnectingToAp ||
            _connectionState.value is WifiConnectionState.SearchingOnLan
        ) {
            cancelConnectionAttempt()
            return false
        }

        if (!hasLocalNetworkPermission()) {
            Log.w(tag, "Local network permission not granted on API 37+; aborting connectOrDiscover.")
            _connectionState.value = WifiConnectionState.Error(
                context.getString(com.crosssend.app.R.string.wifi_error_local_network_permission)
            )
            return false
        }

        _connectionState.value = WifiConnectionState.SearchingOnLan

        // 1. Try discovering CrossPoint on the local Wi-Fi network (LAN) first
        val detected = autoDetectCrossPoint(preferredUrl = getLanBaseUrl())
        if (detected != null) {
            disconnectFromAp() // Ensure no stale AP network binding remains
            webDavClient.updateBaseUrl(detected)
            setConnectedOnLan(detected)
            return true
        }

        // 2. If not reachable on LAN, connect to the CrossPoint Hotspot AP
        connectToCrossPointAp()
        return false
    }

    fun isConnected(): Boolean {
        return _connectionState.value is WifiConnectionState.ConnectedToCrossPointAp ||
                _connectionState.value is WifiConnectionState.ConnectedOnLocalNetwork
    }

    companion object {
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val WATCHDOG_INTERVAL_MS = 12_000L
        private const val WATCHDOG_PROBE_TIMEOUT_MS = 1_800L
        private const val RESUME_LAN_PROBE_TIMEOUT_MS = 1_500L
        private const val RESUME_DISCOVERY_THROTTLE_MS = 5_000L
    }
}
