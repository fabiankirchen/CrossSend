# CrossSend — Agent Instructions (`AGENTS.md`)

**CrossSend** is a native Android companion app (Kotlin, Jetpack Compose, Material 3 Expressive) for **CrossPoint** E-Ink e-readers (ESP32-based readers running CrossPoint firmware). It converts web articles, WebView-rendered pages (for paywalled/JS-heavy/authenticated sites), RSS/Atom feeds, EPUBs, PDFs, and lock-screen images into E-Ink-optimized **EPUB** or **XTCH** page bundles and transfers them to the reader over Wi-Fi (AP or Home Wi-Fi / STA).

- **User communication**: Respond in German unless the user writes in another language. Keep code, comments, identifiers, and tests in clean English.
- **UI localization**: Every user-facing string must exist in **both** English (`app/src/main/res/values/`) and German (`app/src/main/res/values-de/`). Never hardcode user-visible strings in Kotlin.
- **Device installs**: **Never** install builds on a connected Android device (`adb install`) unless the user explicitly asks for installation. Always run local tests, Lint, and builds first.

---

## 1. Tech Stack & Build Configuration

Source of truth: [app/build.gradle.kts](app/build.gradle.kts), [gradle.properties](gradle.properties).

| Property | Value |
| :--- | :--- |
| Package / App ID | `com.crosssend.app` |
| SDK Levels | `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26` |
| Language / JVM | Kotlin, Java 17 (`JvmTarget.JVM_17`) |
| UI Framework | Jetpack Compose (`compose-bom:2026.09.00`) + explicit `androidx.compose.material3:material3:1.5.0-rc01` |
| Persistence | Room (`2.8.5`) via KSP (`AppDatabase`, `DocumentDao`, `FeedDao`, `DomainPreferenceDao`) |
| Networking / Parsing | OkHttp `4.12.0`, Jsoup `1.18.1`, WorkManager `2.9.1`, PdfBox-Android `2.0.27.0` (with `bcpkix-jdk15to18` excluded) |
| Lint Policy | `checkReleaseBuilds = true`, `abortOnError = true` (zero Lint errors required!) |

### Verification Commands (macOS / Local)

Set `JAVA_HOME` explicitly when running Gradle from CLI:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew testReleaseUnitTest lintRelease assembleRelease -PallowDebugReleaseSigning=true
```

- Release packaging refuses to build without signing config unless `-PallowDebugReleaseSigning=true` is passed for local verification builds.
- Target **0 unit test failures**, **0 Lint errors**, and **0 Kotlin compiler warnings** before concluding any code change.

---

## 2. Repository Map

For detailed pipeline, protocol, and database internals, read **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** and **[docs/PROTOCOL_AND_FORMATS.md](docs/PROTOCOL_AND_FORMATS.md)** on demand.

```text
app/src/main/java/com/crosssend/app/
├── CrossSendApp.kt / MainActivity.kt        # App singleton graph, NavHost, onResume connection check
├── share/ShareHandlerActivity.kt            # Android Share-Sheet / URL & file intent receiver
├── converter/
│   ├── DocumentProcessor.kt                 # Central orchestrator for Web/EPUB/PDF/BMP -> EPUB/XTCH
│   ├── ArticleExtractor.kt                  # Jsoup readability extractor & strict HTML inline sanitizer
│   ├── WebPageXtchRenderer.kt               # Offscreen WebView -> paginated E-Ink XTCH page renderer
│   ├── XtcBuilder.kt / EpubBuilder.kt       # Binary .xtch/.xtc packager & reflowable EPUB generator
│   ├── PdfExtractor.kt                      # PDF text/cover extraction & optional XTCH rendering
│   └── EInkImageOptimizer.kt                # SSRF-safe downloader, Floyd-Steinberg dithering, BMP export
├── network/
│   ├── CrossPointWifiManager.kt             # AP/STA dual-network routing, 12s watchdog, HTTPS interceptor
│   ├── CrossPointDeviceClient.kt            # Reader HTTP API (/api/status, /api/files, settings, OTA)
│   ├── WebDavClient.kt                      # WebDAV uploads/listings + WebSocket (:81) fast transfers
│   └── RssFeedParser.kt                     # RSS 2.0 / Atom feed parser
├── sync/
│   ├── SyncManager.kt                       # Serialized upload queue + per-document progress tracking
│   └── RssSyncWorker.kt                     # WorkManager periodic RSS sync
├── data/
│   ├── local/                               # Room entities & DAOs (AppDatabase)
│   └── model/                               # DocumentType, RenderMode, SyncStatus enums
├── ui/
│   ├── theme/                               # M3 dynamic color scheme & typography
│   ├── components/                          # BottomToolbar, DocumentCard/CoverCard, PreviewDialog, ArticleWebViewDialog
│   └── screens/
│       ├── MainScreen.kt                    # Unified active stream + FilterChips + search + top-right Archive badge
│       ├── ArchiveScreen.kt                 # 90-day archive subpage (Grid/List, multi-select delete, resend)
│       ├── FeedsScreen.kt / FeedsTab.kt     # RSS/Atom feed reader & subscriptions
│       ├── AppSettingsScreen.kt             # App preferences, domain render rules, privacy notice
│       └── CrossPointDeviceSettingsScreen.kt# Device settings, sleep-screen BMP, Plugin Store & WebView bridge
└── util/                                    # DebugDumpManager, DocumentPreviewReader, EpubCoverLoader, RemoteImageLoader
```

String resources are split by domain (`strings.xml`, `strings_main.xml`, `strings_feeds.xml`, `strings_device.xml` in `values/` and `values-de/`). Do not leave unused strings or colors behind (`UnusedResources` check).

---

## 3. UI & Material 3 Expressive Guidelines

- **Strict Material Design 3 / M3 Expressive**:
  - `MainScreen.kt` uses a single unified document stream (articles + books) with M3 `FilterChip`s (`All`, `Articles`, `Books`, `Unsent`) below the search bar, a top-right Archive `FilledTonalIconButton` navigating to `ArchiveScreen.kt`, and **no** bottom `NavigationBar` or `NavigationRail`.
  - `CrossSendBottomToolbar` is the sole floating bottom bar (using an M3 `FloatingActionButton` for adding content and `CircularWavyProgressIndicator` / `LinearWavyProgressIndicator` where progress is shown).
  - Support Android 17 predictive back via `PredictiveBackHandler` to close selection modes, filters, search, and subpages cleanly.
- **Material3 `1.5.0-rc01` API quirks** (verified against bundled AAR):
  - `rememberBottomSheetState(...)` uses parameter **`enabledValues`** (not `allowedValues`).
  - Prefer `Slider(state = rememberSliderState(...), ...)` with `onValueChangeFinished` over deprecated `Slider(value = ...)`.
- **Lint hygiene in Composables**:
  - Use `LocalResources.current` (not `context.getString(...)`) to avoid `LocalContextGetResourceValueCall` errors.
  - Use `LocalLocale.current.platformLocale` (not `Locale.getDefault()`) in Composables to avoid `NonObservableLocale` errors.
  - Always place `modifier: Modifier = Modifier` as the **first optional parameter** (`ModifierParameter`).
  - Pass explicit `Locale.getDefault()` to `String.format(...)` in non-Composable utility code (`DefaultLocale`).

---

## 4. Security, Networking & Privacy Rules

1. **External Internet vs. Local E-Reader**:
   - External internet HTTP calls (`CrossPointWifiManager.getInternetOkHttpClientBuilder()`) **must** enforce `https://` (`req.isHttps`) and route DNS through `EInkImageOptimizer.wrapWithSafeDns(...)` (blocking loopback, link-local, site-local/RFC1918, and multicast IPs against SSRF).
   - `network_security_config.xml` allows cleartext HTTP only for CrossPoint local discovery/LAN communication (`192.168.4.1`, `.local`, and dynamic DHCP RFC1918 reader IPs via UDP `:8134`). External cleartext HTTP must remain blocked in Kotlin.
   - Always sanitize remote file/folder paths via `WebDavClient.sanitizeRemotePath(...)` and keep local file reads/writes within validated root directories (`ensureInsideDirectory`).
2. **HTML & WebView Sanitization**:
   - `ArticleExtractor.sanitizeInlineFormatting` must strip all `on*` event attributes, unsafe schemes (`javascript:`, `vbscript:`, `data:`, including obfuscated whitespace/control characters), and active/embed tags (`script`, `style`, `noscript`, `iframe`, `object`, `embed`, `form`, `input`, `button`, `img`).
   - WebViews must keep `allowFileAccess = false`, `allowContentAccess = false`, and `MIXED_CONTENT_NEVER_ALLOW`.
3. **CrossPoint Plugin Sandbox (`CrossPointDeviceSettingsScreen.kt`)**:
   - JS bridge (`ScopedPluginJsBridge`) must scope storage to the active plugin's `folderName`, bind `enqueueJob` strictly to `plugin.folderName`, require declared permissions or explicit user consent for `crypto`, and allow `http://` only to the verified local CrossPoint device host.
   - Non-official catalog hosts (`!= github.com / raw.githubusercontent.com`) must show the external host badge, prompt an explicit user confirmation dialog before install, and verify optional SHA-256 checksums.
   - Escape injected JS strings safely (`JSONObject.quote(...)` + `</` -> `<\/`).
4. **No PII or Metadata Leaks**:
   - Keep launcher PNGs (`icon/*.png`, `mipmap-*/*.png`) stripped of XMP/EXIF/text chunks (`iTXt`, `tEXt`, `zTXt`, `eXIf`).
   - Never commit personal paths, local machine names, emails, or keystore credentials; keep `backup_rules.xml` and `data_extraction_rules.xml` excluding WebView cookies, RSS DBs, and Wi-Fi credentials.
