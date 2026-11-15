<p align="center">
  <img src="icon/readme_icon.png" alt="CrossSend Icon" width="120">
</p>

<h1 align="center">CrossSend</h1>

<p align="center">
  <a href="https://github.com/fabiankirchen/CrossSend/releases/latest">
    <img src="https://img.shields.io/github/v/release/fabiankirchen/CrossSend?label=Release&style=for-the-badge&color=2e7d32" alt="Release">
  </a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/fabiankirchen/CrossSend">
    <img src="https://img.shields.io/badge/Obtainium-Add_App-007ACC?style=for-the-badge&logo=android&logoColor=white" alt="Obtainium">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-lightgrey?style=for-the-badge&logo=android" alt="Platform">
</p>

Native Android companion app for the **CrossPoint E-Ink Reader** ([`crosspoint-reader/crosspoint-reader`](https://github.com/crosspoint-reader/crosspoint-reader)).  
Converts web articles, JS/paywalled pages (via in-app WebView), RSS/Atom feeds, newsletters, PDF/EPUB/text documents, and images into E-Ink-optimized **EPUBs**, pixel-accurate **`.xtch` page bundles**, and **1-bit sleep screens (`.bmp`)**, and transfers them wirelessly to your reader.

> [!NOTE]
> **Transparency:** I am not a programmer — the codebase is **vibe-coded** with AI assistance. The concept, UX, features, and real-world testing were all designed and verified by me.  
> CrossSend is an independent open-source community project and is not affiliated with CrossPoint or any hardware manufacturer.

---

## Installation

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/fabiankirchen/CrossSend">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/fabiankirchen/CrossSend/releases/latest">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="54">
  </a>
</p>

*Tap **Get it on Obtainium** (recommended for automatic updates) or add `https://github.com/fabiankirchen/CrossSend` manually in [Obtainium](https://github.com/ImranR98/Obtainium). Alternatively, download the latest APK directly from **[Releases](https://github.com/fabiankirchen/CrossSend/releases/latest)**.*

---

## Hardware & Firmware Compatibility

CrossSend aims to support **all devices supported by the official CrossPoint firmware** (automatically adapting to the display resolution reported by `/api/status`).  
Compatible **CrossPoint firmware forks** should also work, though they are **not individually tested**.

---

## Key Features

* **Web Articles (`.epub` & `.xtch`):** Share links via the Android Share-Sheet. Choose per article or per domain between clean **Reading Mode (`.epub`)** and **Original Website Layout (`.xtch`)** rendered via offscreen WebView, plus an **in-app login WebView** for paywalled or JS-heavy sites.
* **RSS & Atom Feeds:** Subscribe to feeds, check reading times, and save articles to your E-Ink library in one tap.
* **Email & Newsletters:** Share newsletters directly to CrossSend with automatic header cleanup and clean bylines.
* **1-Bit Sleep-Screen Generator:** Converts images and covers with Atkinson or Floyd-Steinberg dithering into compact 1-bit monochrome `.bmp` files and uploads them straight to `/sleep/`.
* **Fast Wireless Transfer & 2-Way Sync:** Auto-discovers your reader on Home Wi-Fi (`crosspoint.local` / UDP `:8134`) or AP mode (`192.168.4.1`), transfers via WebSocket (`:81`), HTTP, or WebDAV, and syncs deletions with a 90-day local archive.
* **SD Card Explorer & Plugins:** Browse, upload, and download files on the reader's SD card, upload vector fonts (`.cpfont`/`.ttf`/`.otf`), and manage CrossPoint plugins in a sandboxed WebView.
* **Local & Private:** No cloud, no tracking, no external servers — conversions and transfers run locally on your device (HTTPS-only with SSRF protection for web fetches).

---

## Reader Folder Structure

* `/crosssend/articles/` – Web articles & newsletters (`.epub` / `.xtch`)
* `/crosssend/books/` – Books & PDFs
* `/crosssend/texts/` – Notes & Markdown texts
* `/sleep/` – 1-bit monochrome sleep-screen BMPs
* `/.crosspoint/plugins/` & `/plugins/` – CrossPoint plugins

For technical details, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/PROTOCOL_AND_FORMATS.md](docs/PROTOCOL_AND_FORMATS.md).

---

## Building from Source

Requires Android SDK 37 (`minSdk = 26`) and JDK 21:

```bash
# Run unit tests & lint
./gradlew testReleaseUnitTest lintRelease

# Build debug APK
./gradlew assembleDebug
```

---

## License
This project is licensed under the [MIT License](LICENSE).
