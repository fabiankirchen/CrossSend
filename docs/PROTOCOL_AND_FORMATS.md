# CrossPoint Protocol, Binary Formats & Plugin Bridge Specification

Technical reference for implementing compatible clients (Android, iOS, iPadOS, macOS, Desktop) and firmware integrations for **CrossPoint** E-Ink e-readers. Derived directly from the CrossSend reference implementation.

---

## 1. Device Discovery & Connection Modes

CrossPoint readers operate in two Wi-Fi modes:

| Mode | Base URL / Host | Notes |
| :--- | :--- | :--- |
| **AP Mode (Hotspot)** | `http://192.168.4.1` | Reader hosts its own Wi-Fi AP (no internet access). Clients on mobile OSes must route external article/RSS fetches over cellular/other interfaces while keeping reader traffic bound to the AP Wi-Fi. |
| **STA Mode (Home Wi-Fi)** | `http://<reader-ip>` or mDNS | Reader joins the local LAN. Discovery via UDP broadcast (`:8134`) and mDNS `.local` probing. |

### 1.1 Candidate mDNS Hostnames

When probing on LAN, CrossSend checks `/api/status` on:
`crosspoint.local`, `picco.local`, `crossink.local`, `papyrix.local`, `crossmux.local`, `inx.local`, `snapix.local`, `crossplay.local`, `xteink.local`, `boox.local`, `eegoread.local`, `murphy.local`.

### 1.2 UDP Broadcast Discovery (Port `8134`)

1. Client sends UTF-8 payload `"hello"` via UDP to `255.255.255.255:8134` and all interface subnet broadcast addresses.
2. Compatible readers reply with a UTF-8 string starting with a known device/firmware prefix (`crosspoint`, `crossplay`, `crossink`, `crossmux`, `papyrix`, `snapix`, `inx`, `xteink`, `picco`, `pico`, `boox`, `eegoread`, `m5paper`, `reterminal`, `murphy`), typically formatted as:
   ```text
   crosspoint (on <hostname>);81
   ```
3. The optional `;port` suffix indicates the auxiliary/WebSocket port (usually `81`). The client verifies `GET http://<sender-ip>/api/status` (or `http://<sender-ip>:<port>/api/status` if `port` is neither `80` nor `81`).

---

## 2. Supported Hardware Profiles & Display Geometries

If `/api/status` does not return explicit `width`/`height` fields, fall back to the model profile:

| Profile ID | Models | Width (px) | Height (px) | Diagonal |
| :--- | :--- | :---: | :---: | :---: |
| `X4` (default) | Xteink X4 / X4 Pro / X4 Classic | 480 | 800 | 4.3" |
| `PICCO` | Onyx Boox Picco (Pico) | 480 | 800 | 3.97" |
| `X3` | Xteink X3 | 528 | 792 | 3.7" |
| `EEGOREAD_A4` | EEGOREAD A4 / A4 Lite | 552 | 768 | 3.98" |
| `RETERMINAL` | Seeed reTerminal Sticky / Metalio E-Ink 4 | 480 | 800 | 3.97" |
| `M5PAPER` | M5Stack M5Paper / M5PaperS3 | 540 | 960 | 4.7" |
| `MURPHY_M4` | Murphy M4 | 480 | 800 | 4.0" |

---

## 3. CrossPoint HTTP REST, WebDAV & WebSocket (`:81`) API

All endpoints run over cleartext `http://` (or `ws://`) on the local CrossPoint device. All remote paths must be sanitized (normalized `/`-prefixed, no `..` traversal, no NUL bytes).

### 3.1 Device Status, Settings, Fonts & Plugins

| Endpoint | Method | Request / Response Format |
| :--- | :--- | :--- |
| `/api/status` | `GET` | Returns JSON: `version`/`firmware`, `firmwareFamily`/`family`/`fork`, `device`/`board`/`model`/`hostname`, `width`/`screenWidth`/`screen_width`, `height`/`screenHeight`/`screen_height`, `ip`, `mode`/`wifi_mode`, `rssi`, `freeHeap`/`free_heap`, `battery`, `uptime`, `hardwareMac`, `pluginApi`/`plugin_api`, `pluginCapabilities` (array), `vectorFonts`/`vector_fonts` (bool). |
| `/api/settings` | `GET` | Returns JSON array/object of setting descriptors (`key`, `name`, `category`, `type` in `toggle\|enum\|value\|string\|bool\|int`, `value`, `options`, `min`, `max`, `step`). |
| `/api/settings` | `POST` | JSON body `{ "<key>": <value> }` (`application/json`). |
| `/api/plugins` | `GET` | Returns installed SD plugins (`name`, `title`, `mount`). |
| `/api/fonts/upload` | `POST` | `multipart/form-data` with fields `family` (string) and `file` (`.cpfont`, `.ttf`, `.otf`, `.ttc`). |
| `/api/crypto` | `POST` | JSON payload (max 64 KB UTF-8); returns JSON result. |
| `/api/plugin-jobs` | `GET` | Returns JSON array of queued/active plugin jobs. |
| `/api/plugin-jobs` | `POST` | JSON body `{ "plugin": "<folderName>", "action": "<action>", "args": { ... } }`. |

### 3.2 Fast Binary Upload via WebSocket (Port `81`)

CrossSend prefers WebSocket fast upload on `ws://<host>:81/` before falling back to HTTP:

1. **Client → Server (Text Frame):**
   ```text
   START:<safeFileName>:<fileSizeBytes>:<normalizedDir>
   ```
   (`safeFileName` strips NUL, `/`, `\`, `:`, `\r`, `\n`.)
2. **Server → Client (Text Frame):** `READY` (or `ERROR:<message>`).
3. **Client → Server (Binary Frames):** Stream raw file bytes in chunks (e.g. 8–32 KB).
4. **Server → Client (Text Frames):** Optional `PROGRESS:<receivedBytes>:<totalBytes>`, followed by `DONE` on completion (or `ERROR:<message>`).

### 3.3 File Management (Native HTTP + WebDAV Fallbacks)

| Operation | Primary Native HTTP Endpoint | WebDAV Fallback |
| :--- | :--- | :--- |
| **Upload file** | `POST /upload?overwrite=1` (root) or `POST /upload?dir=<dir>&path=<dir>&overwrite=1` | `PUT <remotePath>` |
| **Create dir** | `POST /mkdir` (form-urlencoded: `name`, `path`) | `MKCOL <remotePath>` |
| **List dir** | `GET /api/files` or `GET /api/files?path=<dir>` → JSON `[{ "name", "isDirectory", "size" }]` | `PROPFIND <remotePath>` (`DAV:` XML) |
| **Download** | `GET /download?path=<encodedPath>` | `GET <remotePath>` |
| **Delete** | `POST /delete` (form-urlencoded: `path`, `paths` JSON array) | `DELETE <remotePath>` |
| **Rename** | `POST /rename` (form-urlencoded: `path`, `name`) | — |
| **Move** | `POST /move` (form-urlencoded: `path`, `dest`) | — |

---

## 4. Binary `.xtch` / `.xth` Format Specification

All multi-byte integers are **Little-Endian**. Reference implementation: [XtcBuilder.kt](../app/src/main/java/com/crosssend/app/converter/XtcBuilder.kt).

### 4.1 Container Layout (`.xtch`)

1. **Header (56 bytes at offset `0`):**
   | Offset | Size | Type | Field / Value |
   | :---: | :---: | :--- | :--- |
   | `0` | 4 | `u32` | Magic `0x48435458` (`"XTCH"`) |
   | `4` | 1 | `u8` | `versionMajor` (`1`) |
   | `5` | 1 | `u8` | `versionMinor` (`0`) |
   | `6` | 2 | `u16` | `pageCount` (max `65535`) |
   | `8` | 1 | `u8` | `readDirection` (`0` = Left-to-Right) |
   | `9` | 1 | `u8` | `hasMetadata` (`1`) |
   | `10` | 1 | `u8` | `hasThumbnails` (`0`) |
   | `11` | 1 | `u8` | `hasChapters` (`0`) |
   | `12` | 4 | `u32` | `currentPage` (`1`) |
   | `16` | 8 | `u64` | `metadataOffset` (`56`) |
   | `24` | 8 | `u64` | `indexOffset` (`312`) |
   | `32` | 8 | `u64` | `dataOffset` (`312 + pageCount * 16`) |
   | `40` | 8 | `u64` | `thumbOffset` (`0`) |
   | `48` | 8 | `u64` | `chapterOffset` (`0`) |

2. **Metadata Block (256 bytes at offset `56`):**
   | Offset (rel) | Size | Field |
   | :---: | :---: | :--- |
   | `0` | 128 | `title` (UTF-8, null-padded) |
   | `128` | 64 | `author` (UTF-8, null-padded) |
   | `192` | 32 | `publisher` (UTF-8, null-padded) |
   | `224` | 16 | `language` (UTF-8 BCP-47 tag, null-padded) |
   | `240` | 4 | `createTimeSec` (`u32` Unix timestamp) |
   | `244` | 2 | `coverPage` (`u16`, `0`) |
   | `246` | 2 | `chapterCount` (`u16`, `0`) |
   | `248` | 8 | `reserved` (`0`) |

3. **Page Index Table (`pageCount * 16` bytes at offset `312`):**
   Each 16-byte entry: `offset` (`u64`), `size` (`u32`), `width` (`u16`), `height` (`u16`).

4. **Page Data Blobs (at `dataOffset`):** Concatenated `XTH\0` blobs.

### 4.2 `XTH\0` Page Blob (2-Bit Grayscale, Vertical Scan)

1. **22-byte Header:**
   - `0..3`: Magic `0x00485458` (`"XTH\0"`)
   - `4..5`: `width` (`u16`)
   - `6..7`: `height` (`u16`)
   - `8`: `colorMode` (`u8`, `0`)
   - `9`: `compression` (`u8`, `0`)
   - `10..13`: `dataSize` (`u32`, equals `planeSize * 2`)
   - `14..21`: `md5` (`u64`, optional `0`)
2. **Dual Bitplanes (`plane1` followed by `plane2`):**
   - `colBytes = (height + 7) / 8`, `planeSize = colBytes * width`.
   - Columns are stored **right-to-left** (`x = width - 1 - colIdx` for `colIdx in 0..<width`).
   - Within each column, 8 vertical pixels are packed per byte, MSB first (`bitMask = 1 << (7 - (y & 7))`).
   - **2-bit level encoding** `(bit1 << 1) | bit2` (`bit1` in `plane1`, `bit2` in `plane2`):
     - `0` (`0b00`): White (`255`)
     - `1` (`0b01`): Dark Gray (`85`)
     - `2` (`0b10`): Light Gray (`170`)
     - `3` (`0b11`): Black (`0`)

### 4.3 E-Ink Luma Curve & Atkinson Dithering

1. Compute ITU-R BT.601 luminance: `Y = 0.299*R + 0.587*G + 0.114*B`.
2. Contrast snap: `Y >= 242 -> 255`, `Y <= 28 -> 0`, otherwise `clamp((Y - 128) * 1.15 + 128, 0, 255)`.
3. Quantize with thresholds `212.5` (White), `127.5` (Light Gray), `42.5` (Dark Gray), `< 42.5` (Black), diffusing `err = (old - new) / 8` using the 6-neighbor Atkinson kernel (`(x+1,y)`, `(x+2,y)`, `(x-1,y+1)`, `(x,y+1)`, `(x+1,y+1)`, `(x,y+2)`).

---

## 5. Plugin WebView JS Bridge (`window.CrossPoint`)

Hosted plugin UIs communicate with the native app via `window.CrossPoint` (`ScopedPluginJsBridge` in [CrossPointDeviceSettingsScreen.kt](../app/src/main/java/com/crosssend/app/ui/screens/CrossPointDeviceSettingsScreen.kt)):

| Method | Signature | Behavior & Security Constraints |
| :--- | :--- | :--- |
| `readFile` | `(path: string) -> string` | Reads UTF-8 file scoped strictly inside the active plugin's SD folder. |
| `writeFile` | `(path: string, rawData: string) -> bool` | Writes UTF-8 text (max 5 MB) scoped to the active plugin folder. |
| `writeFileEncoded` / `writeFileWithEncoding` | `(path: string, data: string, isBase64: bool) -> bool` | Decodes Base64 when `isBase64 == true` (max 7 MB encoded / 5 MB decoded). |
| `relay` | `(method, url, headersJson, body) -> string (JSON)` | Proxies HTTP requests: allows `http://` **only** to the verified CrossPoint device host; external URLs require `https://` + SSRF-safe DNS. Response capped at 10 MB. |
| `fetchToSd` | `(url, dest, headersJson) -> string (JSON)` | Downloads a resource (max 10 MB, same URL policy as `relay`) and writes it directly to `dest` inside the plugin folder. |
| `crypto` | `(requestJson: string) -> string (JSON)` | Forwards to `/api/crypto`; requires declared `"crypto"` permission in plugin manifest or explicit user consent. |
| `enqueueJob` | `(pluginName, action, argsJson) -> string (JSON)` | Forwards to `/api/plugin-jobs`; rejects cross-plugin `pluginName != activePlugin.folderName` with HTTP 403. |
