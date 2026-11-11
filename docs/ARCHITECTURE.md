# CrossSend Architecture & Internals

Reference document linked from [AGENTS.md](../AGENTS.md). Read relevant sections on demand instead of scanning the entire codebase. For wire protocols, binary `.xtch`/`.xth` layout, hardware profiles, and JS bridge specs, see **[PROTOCOL_AND_FORMATS.md](PROTOCOL_AND_FORMATS.md)**.

---

## 1. Document & E-Ink Rendering Pipelines

Entry point: `DocumentProcessor` (`app/src/main/java/com/crosssend/app/converter/DocumentProcessor.kt`).

- **Supported inputs**:
  - Web URLs shared via `ShareHandlerActivity`, pasted in the UI, or queued from RSS/Atom feeds (`FeedsTab.kt`).
  - Authenticated / JS-heavy / paywalled web pages captured via `ArticleWebViewDialog` (renders live WebView session with user cookies).
  - Local `EPUB`, `PDF`, and image files (including sleep-screen BMP conversion).
- **Render modes (`RenderMode`)**:
  - **Reflowable EPUB (`EpubBuilder`)**: HTML extracted cleanly via `ArticleExtractor` (Jsoup readability heuristics, subtitle/byline cleanup, lazy-image resolution, inline formatting sanitizer), images downscaled & contrast-tuned via `EInkImageOptimizer`, packaged as valid EPUB.
  - **Rendered XTCH (`WebPageXtchRenderer` + `XtcBuilder`)**: Offscreen Android WebView loads article/page, injects CSS/JS pagination helpers (respecting backgrounds across page breaks, keeping headings/boxes/images intact without mid-element slicing, status bar geometry matching CrossPoint reader display), captures bitmap slices, applies E-Ink quantization/dithering, and packs binary `.xtch`/`.xtc` containers.
  - **PDF (`PdfExtractor`)**: Extracts text/metadata via `pdfbox-android` (with BouncyCastle `bcpkix` excluded to keep APK clean of unused trust-manager classes) or converts pages for E-Ink display.
- **Local inspection & dumps**: `DebugDumpManager` and `DocumentPreviewReader` allow inspecting generated `.epub` and `.xtch` pages directly inside `DocumentPreviewDialog`.

---

## 2. Networking, Connection Watchdog & Sync

- **Dual-network management (`CrossPointWifiManager`)**:
  - Tracks active internet network vs. CrossPoint Wi-Fi network using `ConnectivityManager.NetworkCallback` + `ConcurrentHashMap` (does **not** use deprecated `connectivityManager.allNetworks`).
  - Supports **AP mode** (`192.168.4.1`, `crosspoint.local`) and **Home Wi-Fi / STA mode** (dynamic RFC1918 reader IP via UDP broadcast discovery on port `:8134`).
  - **Automatic disconnect detection**: `lanMonitorCallback` clears AP/STA state immediately on Wi-Fi loss; a `12s` periodic reachability watchdog marks the reader disconnected after consecutive probe failures; `WebDavClient` and `CrossPointDeviceClient` call `reportDeviceUnreachable()` on network failure; `MainActivity.onResume()` triggers `verifyConnectionOnResume()`.
- **Transfers (`WebDavClient`, `CrossPointDeviceClient`, `SyncManager`)**:
  - `WebDavClient` caches network-bound `OkHttpClient` instances for HTTP/WebDAV (`MKCOL`, `PUT`, `PROPFIND`, `DELETE`) and WebSocket uploads (port `81`), applying `sanitizeRemotePath(...)` consistently across all endpoints.
  - `SyncManager` exposes per-document `StateFlow` sync progress rendered by `DocumentCard` (`LinearWavyProgressIndicator`).
  - Uploaded documents automatically mark `SyncStatus.SYNCED`; archived items move to the 90-day archive (`ArchiveScreen.kt`).

---

## 3. Database Schema (`AppDatabase`)

Room database in `app/src/main/java/com/crosssend/app/data/local/`:

- `DocumentEntity` / `DocumentDao`: active & archived documents (`DocumentType`, `RenderMode`, `SyncStatus`, local paths, sizes, timestamps).
- `FeedEntity` / `FeedDao` & `FeedArticleEntity`: RSS/Atom subscriptions and cached feed items updated by `RssFeedParser` + `RssSyncWorker`.
- `DomainPreferenceEntity` / `DomainPreferenceDao`: per-domain user preferences (e.g., preferred render mode `EPUB` vs. `XTCH` or WebView capture mode).

---

## 4. CrossPoint v2.0 Device Settings & SD Plugin System

Managed in `app/src/main/java/com/crosssend/app/ui/screens/CrossPointDeviceSettingsScreen.kt`:

- **Native & Legacy Device Settings**: Communicates with CrossPoint firmware endpoints (`/api/settings`, `/api/status`, `/api/files`, `/sleep`) to configure reader preferences, sleep-screen 1-bit/24-bit BMPs, and file management.
- **Plugin Catalog & Store**:
  - Loads plugin metadata from catalog sources, highlights source hostname (`extractHost`) and third-party badges, prompts explicit confirmation before installing non-GitHub third-party plugins, and validates optional SHA-256 hashes before writing to SD card.
  - Probes the active SD plugin root (`/plugins` vs. `/.crosspoint/plugins`) and loads installed plugins concurrently via Kotlin coroutines.
- **Integrated Plugin WebView (`ScopedPluginJsBridge`)**:
  - Exposes `window.CrossPoint` safely to local plugin UIs with JSON-quoted plugin identity (`folderName`).
  - `writeFileEncoded` encodes text payloads in Base64 unconditionally on the JS side before decoding in Kotlin; legacy `writeFile` accepts UTF-8 directly.
  - `resolveDeviceHttpUrlIfAllowed` supports both AP and STA Home Wi-Fi modes while pinning requests to the verified CrossPoint reader host/IP.
  - `enqueueJob` locks `targetPlugin` strictly to `plugin.folderName`; `crypto` checks manifest `permissions`/`capabilities` (or explicit per-plugin user consent toggle).
