package com.crosssend.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
