package com.crosssend.app.network

import android.net.Network
import okhttp3.Interceptor
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Validates and normalizes local CrossPoint reader hosts/URLs so cleartext HTTP traffic
 * is strictly confined to RFC 1918 LAN / AP addresses and valid `.local` mDNS hostnames,
 * while blocking loopback, link-local metadata (`169.254.169.254`), and public hosts.
 */
object LocalDeviceHostValidator {
    private val MDNS_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9_-]{0,61}[a-z0-9])?$")
    private val STRICT_IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /**
     * Returns `true` only for local private LAN/AP addresses (RFC 1918 IPv4, IPv6 ULA/link-local,
     * or IPv4-mapped RFC 1918), rejecting loopback, wildcard, multicast, link-local IPv4
     * (`169.254.0.0/16`), and public addresses.
     */
    fun isAllowedLocalAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) {
            return false
        }
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return b0 == 10 ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168)
        }
        if (bytes.size == 16) {
            val isV4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            if (isV4Mapped) {
                val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                return isAllowedLocalAddress(v4)
            }
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val isUniqueLocal = (b0 and 0xFE) == 0xFC
            val isLinkLocal = addr.isLinkLocalAddress || (b0 == 0xFE && (b1 and 0xC0) == 0x80)
            return isUniqueLocal || isLinkLocal
        }
        return false
    }

    /**
     * Checks whether [host] identifies a valid local CrossPoint device (RFC 1918 IP or `.local` mDNS).
     */
    fun isLocalDeviceHost(host: String): Boolean = isLocalDeviceHost(host, null)

    /**
     * Checks whether [host] identifies a valid local CrossPoint device, resolving non-`.local`
     * hostnames through [apNetwork] when provided.
     */
    fun isLocalDeviceHost(host: String, apNetwork: Network?): Boolean {
        val cleanHost = host.lowercase(Locale.ROOT).trim().removeSurrounding("[", "]")
        if (cleanHost.isBlank() ||
            cleanHost.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' } ||
            cleanHost == "localhost" || cleanHost.endsWith(".localhost") || cleanHost == "0.0.0.0" ||
            cleanHost == "::" || cleanHost == "::1" || cleanHost == "0:0:0:0:0:0:0:1" ||
            cleanHost.startsWith("127.") || cleanHost.startsWith("::ffff:127.")
        ) {
            return false
        }
        if (cleanHost.startsWith("0x") ||
            (cleanHost.all { it.isDigit() || it == '.' } && !cleanHost.matches(STRICT_IPV4_REGEX)) ||
            cleanHost.split('.').any { it.length > 1 && it.startsWith('0') && it.all { c -> c.isDigit() } }
        ) {
            return false
        }
        if (cleanHost == "internal" || cleanHost.endsWith(".internal")) {
            return false
        }
        if (cleanHost.endsWith(".local")) {
            if (cleanHost.length > 253 || cleanHost == "localhost.local") return false
            val labels = cleanHost.split('.')
            return labels.size >= 2 && labels.all { it.matches(MDNS_LABEL_REGEX) }
        }
        return try {
            val addrs = if (apNetwork != null) {
                apNetwork.getAllByName(cleanHost)
            } else {
                InetAddress.getAllByName(cleanHost)
            }
            addrs.isNotEmpty() && addrs.all { isAllowedLocalAddress(it) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates and normalizes [rawUrl] to `http://<host>` or `http://<host>:<port>` (with
     * trailing slashes stripped), accepting `http://` or schemeless local hosts/IPs and rejecting
     * non-HTTP schemes, embedded credentials, invalid ports, loopback/metadata, and public hosts.
     */
    fun normalizeLocalDeviceUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
            return null
        }

        val candidate = if (trimmed.contains("://")) {
            val scheme = trimmed.substringBefore("://").lowercase(Locale.ROOT)
            if (scheme != "http") return null
            trimmed
        } else {
            "http://$trimmed"
        }

        return try {
            val uri = URI(candidate)
            if (!uri.scheme.equals("http", ignoreCase = true)) return null
            if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (!uri.rawPath.isNullOrEmpty()) return null

            val authority = uri.rawAuthority ?: return null
            if (authority.contains('@')) return null

            if (authority.startsWith("[")) {
                val closeIdx = authority.indexOf(']')
                if (closeIdx < 0) return null
                val rest = authority.substring(closeIdx + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return null
                    val portStr = rest.substring(1)
                    if (portStr.isEmpty() || portStr.any { !it.isDigit() } || uri.port !in 1..65535) {
                        return null
                    }
                }
            } else if (authority.contains(':')) {
                val portStr = authority.substringAfter(':')
                if (portStr.isEmpty() || portStr.any { !it.isDigit() } || uri.port !in 1..65535) {
                    return null
                }
            } else if (uri.port != -1) {
                return null
            }

            val rawHost = uri.host ?: return null
            if (!isLocalDeviceHost(rawHost)) return null

            val cleanHost = rawHost.removeSurrounding("[", "]").lowercase(Locale.ROOT)
            val formattedHost = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
            val portSuffix = if (uri.port in 1..65535) ":${uri.port}" else ""
            "http://$formattedHost$portSuffix"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns an OkHttp [Interceptor] that blocks cleartext `http://` requests and redirects
     * targeting any host that is not a validated local CrossPoint device.
     */
    fun createLocalDeviceCleartextGuardInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        if (!url.isHttps && !isLocalDeviceHost(url.host)) {
            throw IOException("Blocked cleartext HTTP to non-local host: ${url.host}")
        }
        val response = chain.proceed(request)
        val finalUrl = response.request.url
        if (!finalUrl.isHttps && !isLocalDeviceHost(finalUrl.host)) {
            response.close()
            throw IOException("Blocked cleartext HTTP to non-local host: ${finalUrl.host}")
        }
        if (response.isRedirect) {
            val location = response.header("Location")
            if (location != null) {
                val resolved = response.request.url.resolve(location)
                if (resolved != null && !resolved.isHttps && !isLocalDeviceHost(resolved.host)) {
                    response.close()
                    throw IOException("Blocked cleartext HTTP to non-local host: ${resolved.host}")
                }
            }
        }
        response
    }
}
