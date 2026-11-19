package com.crosssend.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPointProtocolTest {

    @Test
    fun `parseStatusJson preserves v1x compatibility when v20 fields are missing`() {
        val v1Json = """
            {
              "ip": "192.168.4.1",
              "mode": "AP",
              "rssi": -42,
              "version": "0.9.5",
              "freeHeap": 98304,
              "battery": 85,
              "uptime": 120,
              "device": "Xteink X4"
            }
        """.trimIndent()

        val status = CrossPointDeviceClient.parseStatusJson(v1Json, "192.168.4.1")

        assertEquals("192.168.4.1", status.ip)
        assertEquals("AP", status.wifiMode)
        assertEquals("0.9.5", status.firmwareVersion)
        assertEquals(98304L, status.freeHeap)
        assertEquals(85, status.batteryPercent)
        assertNull(status.pluginApi)
        assertTrue(status.pluginCapabilities.isEmpty())
        assertFalse(status.vectorFonts)
    }

    @Test
    fun `parseStatusJson parses v20 fields with comma-separated capabilities`() {
        val v2Json = """
            {
              "ip": "192.168.1.50",
              "mode": "STA",
              "version": "2.0.0",
              "firmwareFamily": "crosspoint",
              "device": "Xteink X3",
              "pluginApi": 2,
              "pluginCapabilities": "crypto, jobs, fs-scoped, vector-fonts",
              "vectorFonts": true
            }
        """.trimIndent()

        val status = CrossPointDeviceClient.parseStatusJson(v2Json)

        assertEquals("192.168.1.50", status.ip)
        assertEquals("STA", status.wifiMode)
        assertEquals("CrossPoint 2.0.0", status.firmwareVersion)
        assertEquals("X3", status.deviceModel)
        assertEquals(2, status.pluginApi)
        assertEquals(listOf("crypto", "jobs", "fs-scoped", "vector-fonts"), status.pluginCapabilities)
        assertTrue(status.vectorFonts)
    }

    @Test
    fun `parseStatusJson parses v20 JSON array capabilities and snake_case fallback`() {
        val v2ArrayJson = """
            {
              "ip": "10.0.0.12",
              "wifi_mode": "STA",
              "plugin_api": 2,
              "plugin_capabilities": ["crypto", "jobs", " "],
              "vector_fonts": true
            }
        """.trimIndent()

        val status = CrossPointDeviceClient.parseStatusJson(v2ArrayJson)

        assertEquals(2, status.pluginApi)
        assertEquals(listOf("crypto", "jobs"), status.pluginCapabilities)
        assertTrue(status.vectorFonts)
    }

    @Test
    fun `parseStatusJson infers vectorFonts from pluginCapabilities when boolean field is omitted`() {
        val inferredJson = """
            {
              "ip": "192.168.4.1",
              "mode": "AP",
              "version": "2.0.0",
              "pluginApi": 2,
              "pluginCapabilities": ["crypto", "jobs", "vector-fonts"]
            }
        """.trimIndent()

        val status = CrossPointDeviceClient.parseStatusJson(inferredJson)

        assertEquals(2, status.pluginApi)
        assertEquals(listOf("crypto", "jobs", "vector-fonts"), status.pluginCapabilities)
        assertTrue(status.vectorFonts)
    }

    @Test
    fun `buildHttpUploadUrl always appends overwrite=1 for root and nested dirs`() {
        assertEquals(
            "http://192.168.4.1/upload?overwrite=1",
            WebDavClient.buildHttpUploadUrl("http://192.168.4.1/", "/book.epub")
        )
        assertEquals(
            "http://crosspoint.local/upload?dir=%2FBooks%2FFiction&path=%2FBooks%2FFiction&overwrite=1",
            WebDavClient.buildHttpUploadUrl("http://crosspoint.local", "/Books/Fiction/book.epub")
        )
    }

    @Test
    fun `buildWebSocketUploadUrl maps http and https to port 81 and strips path or port`() {
        assertEquals(
            "ws://crosspoint.local:81/",
            WebDavClient.buildWebSocketUploadUrl("http://crosspoint.local")
        )
        assertEquals(
            "ws://192.168.4.1:81/",
            WebDavClient.buildWebSocketUploadUrl("http://192.168.4.1:8080/dav/files")
        )
        assertEquals(
            "wss://reader.example.com:81/",
            WebDavClient.buildWebSocketUploadUrl("https://reader.example.com:8443/api")
        )
        assertNull(WebDavClient.buildWebSocketUploadUrl("ftp://192.168.4.1"))
        assertNull(WebDavClient.buildWebSocketUploadUrl(""))
    }

    @Test
    fun `formatWsStartFrame formats normalized directory and sanitized filename`() {
        assertEquals(
            "START:test.epub:123456:/",
            WebDavClient.formatWsStartFrame("test.epub", 123456L, "/")
        )
        assertEquals(
            "START:sub_article.xtch:4096:/Articles/News",
            WebDavClient.formatWsStartFrame("sub/article.xtch", 4096L, "Articles/News/")
        )
    }

    @Test
    fun `parseWsServerFrame parses READY PROGRESS DONE ERROR and unknown frames`() {
        assertEquals(WebDavClient.Companion.WsServerFrame.Ready, WebDavClient.parseWsServerFrame("READY"))
        assertEquals(WebDavClient.Companion.WsServerFrame.Ready, WebDavClient.parseWsServerFrame("  READY \n"))
        assertEquals(
            WebDavClient.Companion.WsServerFrame.Progress(32768L, 65536L),
            WebDavClient.parseWsServerFrame("PROGRESS:32768:65536")
        )
        assertEquals(WebDavClient.Companion.WsServerFrame.Done, WebDavClient.parseWsServerFrame("DONE"))
        assertEquals(
            WebDavClient.Companion.WsServerFrame.Error("SD card full"),
            WebDavClient.parseWsServerFrame("ERROR:SD card full")
        )
        assertTrue(WebDavClient.parseWsServerFrame("PROGRESS:bad:data") is WebDavClient.Companion.WsServerFrame.Unknown)
        assertTrue(WebDavClient.parseWsServerFrame("PING") is WebDavClient.Companion.WsServerFrame.Unknown)
    }

    @Test
    fun `sanitizeRemotePath strips traversal segments backslashes null bytes and extra slashes`() {
        assertEquals("/etc/passwd", WebDavClient.sanitizeRemotePath("../../etc/passwd"))
        assertEquals("/secret/book.epub", WebDavClient.sanitizeRemotePath("\\..\\secret\\book.epub"))
        assertEquals("/Books/sub/a.xtch", WebDavClient.sanitizeRemotePath("//Books//sub/./a.xtch"))
        assertEquals("/", WebDavClient.sanitizeRemotePath(" "))
        assertEquals("/", WebDavClient.sanitizeRemotePath("../.."))
        assertEquals("/Books/note.txt", WebDavClient.sanitizeRemotePath("Books/note\u0000.txt"))
    }

    @Test
    fun `isAllowedPluginConfigPath restricts config paths to active plugin namespace`() {
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/plugins/wallabag/config.json"))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/plugins/wallabag/token.json"))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/wallabag.json"))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("monthwallpaper", "/plugins", "/.crosspoint/month-wallpaper.json"))

        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/settings.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/wifi.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/opds.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/koreader.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/.crosspoint/other_plugin.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/sleep/cover.bmp"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wallabag", "/plugins", "/"))

        // Prevent namespace collision where a malicious plugin names itself after a protected CrossPoint config store
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wifi", "/plugins", "/.crosspoint/wifi.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("wifi", "/plugins", "/plugins/wifi/config.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("opds", "/plugins", "/.crosspoint/opds.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("koreader", "/plugins", "/.crosspoint/koreader.json"))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginConfigPath("settings", "/plugins", "/.crosspoint/settings.json"))
    }

    @Test
    fun `CrossPointHardwareProfile detects Sticky and EEGO A4 Lite aliases`() {
        val sticky = com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString("Seeed reTerminal Sticky")
        assertEquals(com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.RETERMINAL, sticky)
        assertEquals(480, sticky.screenWidth)
        assertEquals(800, sticky.screenHeight)

        val eego = com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.fromModelString("EEGO A4 Lite")
        assertEquals(com.crosssend.app.converter.EInkImageOptimizer.Companion.CrossPointHardwareProfile.EEGOREAD_A4, eego)
        assertEquals(552, eego.screenWidth)
        assertEquals(768, eego.screenHeight)
    }

    @Test
    fun `isSpecificDetectedModel rejects generic fallback and accepts hardware models`() {
        assertFalse(CrossPointDeviceClient.isSpecificDetectedModel(null))
        assertFalse(CrossPointDeviceClient.isSpecificDetectedModel(""))
        assertFalse(CrossPointDeviceClient.isSpecificDetectedModel("   "))
        assertFalse(CrossPointDeviceClient.isSpecificDetectedModel("CrossPoint Reader"))
        assertFalse(CrossPointDeviceClient.isSpecificDetectedModel("crosspoint"))
        assertTrue(CrossPointDeviceClient.isSpecificDetectedModel("X3"))
        assertTrue(CrossPointDeviceClient.isSpecificDetectedModel("Xteink X4"))
        assertTrue(CrossPointDeviceClient.isSpecificDetectedModel("M5Paper"))
        assertTrue(CrossPointDeviceClient.isSpecificDetectedModel("PICCO"))
    }

    @Test
    fun `resolvePluginScopedPath protects plugin code and manifests on write while allowing config files`() {
        // Reads of manifest.json / plugin.js / device.json are allowed inside plugin namespace
        assertEquals(
            "/plugins/wallabag/manifest.json",
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "manifest.json", forWrite = false)
        )
        assertEquals(
            "/plugins/wallabag/plugin.js",
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "plugin.js", forWrite = false)
        )

        // Writes to config.json and /.crosspoint/<plugin>.json are allowed
        assertEquals(
            "/plugins/wallabag/config.json",
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "config.json", forWrite = true)
        )
        assertEquals(
            "/.crosspoint/wallabag.json",
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "/.crosspoint/wallabag.json", forWrite = true)
        )

        // Writes to manifest.json, plugin.js, device.json, and any .js file must be blocked
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "manifest.json", forWrite = true)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "/plugins/wallabag/MANIFEST.JSON", forWrite = true)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "plugin.js", forWrite = true)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "device.json", forWrite = true)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "sub/helper.js", forWrite = true)
        }

        // Path traversal and protected CrossPoint system files are blocked for both read and write
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "../other/config.json", forWrite = false)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "/.crosspoint/wifi.json", forWrite = false)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "/.crosspoint/settings.json", forWrite = true)
        }
        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.resolvePluginScopedPath("wallabag", "/plugins", "/sleep/cover.bmp", forWrite = true)
        }
    }

    @Test
    fun `filterRelayHeaders strips hop-by-hop headers blank keys and CRLF injection while preserving safe headers`() {
        val rawHeaders = """
            {
              "Host": "evil.example.com",
              "Connection": "keep-alive",
              "Content-Length": "42",
              "Transfer-Encoding": "chunked",
              "Upgrade": "websocket",
              "TE": "trailers",
              "Trailer": "X-Check",
              "Proxy-Authorization": "Basic secret",
              "Proxy-Connection": "keep-alive",
              "   ": "blank-key",
              "Authorization": "Bearer abc123\r\nX-Injected: evil",
              "Accept": "application/json",
              "X-Custom\r\nHeader": "value\nline2"
            }
        """.trimIndent()

        val filtered = com.crosssend.app.ui.screens.filterRelayHeaders(rawHeaders)

        assertFalse(filtered.containsKey("Host"))
        assertFalse(filtered.containsKey("Connection"))
        assertFalse(filtered.containsKey("Content-Length"))
        assertFalse(filtered.containsKey("Transfer-Encoding"))
        assertFalse(filtered.containsKey("Upgrade"))
        assertFalse(filtered.containsKey("TE"))
        assertFalse(filtered.containsKey("Trailer"))
        assertFalse(filtered.containsKey("Proxy-Authorization"))
        assertFalse(filtered.containsKey("Proxy-Connection"))
        assertFalse(filtered.keys.any { it.isBlank() })

        assertEquals("Bearer abc123X-Injected: evil", filtered["Authorization"])
        assertEquals("application/json", filtered["Accept"])
        assertEquals("valueline2", filtered["X-CustomHeader"])
        assertTrue(filtered.values.none { it.contains('\r') || it.contains('\n') })
        assertTrue(filtered.keys.none { it.contains('\r') || it.contains('\n') })
        assertTrue(com.crosssend.app.ui.screens.filterRelayHeaders("").isEmpty())
        assertTrue(com.crosssend.app.ui.screens.filterRelayHeaders("not-json").isEmpty())
    }

    @Test
    fun `isLocalDeviceHost allows valid RFC1918 and mDNS local hosts and rejects internal or obfuscated hosts`() {
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("192.168.4.1", null))
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("192.168.1.50", null))
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("10.0.0.12", null))
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("172.16.0.1", null))
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("172.31.255.254", null))
        assertTrue(com.crosssend.app.ui.screens.isLocalDeviceHost("crosspoint.local", null))

        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("foo.internal", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("0x7f000001", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("0177.0.0.1", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("2130706433", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("localhost", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("127.0.0.1", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("0.0.0.0", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("::1", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("169.254.169.254", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("172.32.0.1", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("8.8.8.8", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost(".local", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("foo..local", null))
        assertFalse(com.crosssend.app.ui.screens.isLocalDeviceHost("", null))
    }

    @Test
    fun `buildPluginHostHtml injects dynamic theme colors and uses css error variable`() {
        val customColors = com.crosssend.app.ui.screens.PluginHostThemeColors(
            background = "#101112",
            surface = "#202122",
            surfaceContainer = "#303132",
            primary = "#AABBCC",
            onPrimary = "#010203",
            primaryContainer = "#405060",
            onPrimaryContainer = "#DDEEFF",
            onSurface = "#F1F2F3",
            onSurfaceVariant = "#C1C2C3",
            outlineVariant = "#707172",
            error = "#FF5544",
            onErrorContainer = "#FFDAD6"
        )
        val html = com.crosssend.app.ui.screens.buildPluginHostHtml(
            folderName = "wallabag",
            pluginJs = "console.log('ok');",
            isDarkTheme = true,
            startErrorPrefix = "Error: ",
            themeColors = customColors
        )

        assertTrue(html.contains("--bg-color: #101112;"))
        assertTrue(html.contains("--primary: #AABBCC;"))
        assertTrue(html.contains("--error: #FF5544;"))
        assertTrue(html.contains("--on-error-container: #FFDAD6;"))
        assertTrue(html.contains("errDiv.style.color = 'var(--error)';"))
        assertFalse(html.contains("#ffb4ab"))
    }

    @Test
    fun `normalizeLocalDeviceUrl normalizes valid local URLs and rejects unsafe inputs`() {
        assertEquals("http://192.168.1.50", LocalDeviceHostValidator.normalizeLocalDeviceUrl("192.168.1.50"))
        assertEquals("http://192.168.4.1", LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://192.168.4.1/"))
        assertEquals("http://crosspoint.local:8080", LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://crosspoint.local:8080/"))
        assertEquals("http://crosspoint.local:8080", LocalDeviceHostValidator.normalizeLocalDeviceUrl("  HTTP://CrossPoint.LOCAL:8080/  "))

        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("https://192.168.1.50"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("ftp://192.168.4.1"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://user:pass@192.168.4.1"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://user@192.168.4.1"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://192.168.4.1/api"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://192.168.4.1/?q=1"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("http://192.168.4.1#frag"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("127.0.0.1"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("localhost"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("169.254.169.254"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("8.8.8.8"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl("example.com"))
        assertNull(LocalDeviceHostValidator.normalizeLocalDeviceUrl(""))
    }

    @Test
    fun `isAllowedPluginDeviceRelay allows read-only allowlisted GET paths on matching local device only`() {
        val apBase = "http://192.168.4.1"
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$apBase/api/status", apBase))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$apBase/api/plugins", apBase))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$apBase/api/plugin-jobs", apBase))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay(" get ", "$apBase/api/status/", apBase))

        val mdnsBase = "http://crosspoint.local:8080"
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$mdnsBase/api/status", mdnsBase))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$mdnsBase/api/plugins", mdnsBase))
        assertTrue(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$mdnsBase/api/plugin-jobs?id=1", mdnsBase))

        // Block non-GET methods even on allowlisted paths
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("POST", "$apBase/api/status", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("PUT", "$apBase/api/plugins", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("DELETE", "$apBase/api/plugin-jobs", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("PATCH", "$apBase/api/status", apBase))

        // Block mutating and sensitive paths
        for (blockedPath in listOf("/api/files", "/api/settings", "/upload", "/delete", "/edit", "/api/ota", "/api/status/extra", "/")) {
            assertFalse(
                "Expected $blockedPath to be blocked",
                com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "$apBase$blockedPath", apBase)
            )
        }

        // Block mismatched host, mismatched port, and https:// scheme
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "http://192.168.1.50/api/status", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "http://192.168.4.1:8080/api/status", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "http://crosspoint.local:8081/api/status", mdnsBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "http://crosspoint.local/api/status", mdnsBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "https://192.168.4.1/api/status", apBase))
        assertFalse(com.crosssend.app.ui.screens.isAllowedPluginDeviceRelay("GET", "https://192.168.4.1/api/status", "https://192.168.4.1"))
    }

    @Test
    fun `mergeExpectedPluginHashes normalizes prioritizes catalog and rejects conflicting manifest hashes`() {
        val merged = com.crosssend.app.ui.screens.mergeExpectedPluginHashes(
            catalogHashes = mapOf(
                "  Plugin.JS  " to "  AABBCC  ",
                "manifest.json" to "112233",
                "   " to "ignored",
                "empty.bin" to "   "
            ),
            manifestHashes = mapOf(
                "plugin.js" to "aabbcc",
                "  Assets/Icon.PNG " to " DDEEFF ",
                "manifest.json" to "   "
            )
        )

        assertEquals(
            mapOf(
                "plugin.js" to "aabbcc",
                "manifest.json" to "112233",
                "assets/icon.png" to "ddeeff"
            ),
            merged
        )

        assertThrows(SecurityException::class.java) {
            com.crosssend.app.ui.screens.mergeExpectedPluginHashes(
                catalogHashes = mapOf("plugin.js" to "aabbcc"),
                manifestHashes = mapOf("PLUGIN.JS" to "deadbeef")
            )
        }
    }
}
