# DT Mini Browser – Android Client

**A tiny, dependency-free Android browser that lets a server do the heavy lifting.**

DT Mini Browser (by DanlTech) is a *server-side rendered* browser for Android. Instead of running a full web engine on your phone, the app talks to a rendering server ([`dt-mini-browser-server`](https://github.com/danltech/dt-mini-browser-server)). The server loads pages in a real Chromium (JavaScript and CSS included) and sends back a compact "display list". The app only draws it.

> 🇩🇪 **Language note:** The user interface is currently **German only**. English (and other languages) are planned. Translations are welcome!

> ⚠️ **Early version (v0.5).** Expect rough edges.

---

## Why?

- 📉 **Light on the device**: no web engine in the app; the APK is only a few dozen KB
- 🕰️ **Runs on old phones**: `minSdk 16` (Android 4.1+)
- 🧱 **Zero dependencies**: plain Java, no AndroidX, no Kotlin, no third-party libraries
- 🛡️ **Ads/trackers are filtered on the server** (common ad and tracking hosts are blocked)

## Features

- Tabs
- Bookmarks and history (last 100 entries, stored locally)
- Text input in forms, tapping links and buttons
- File downloads (routed through the server)
- Certificate info dialog for HTTPS pages
- Configurable server address
- Can be set as the Android default browser (handles `http`/`https` links)
- Debug overlay (for development)

## How it works

```
┌──────────────┐   compact JSON (gzip)   ┌──────────────────┐   ┌─────────┐
│ Android app  │ ◄────────────────────── │  Render server   │ ◄─┤ Website │
│ (draws only) │ ──── taps / typing ───► │ (Playwright +    │   └─────────┘
└──────────────┘                         │  Chromium)       │
                                         └──────────────────┘
```

## Getting started

1. **Set up the server first** → see [dt-mini-browser-server](https://github.com/danltech/dt-mini-browser-server).
2. Install the APK (see *Releases*) or build it yourself (below).
3. In the app, open the menu → **Server-Adresse** and enter your server, e.g. `http://192.168.1.50:8080`
   (Android emulator: `http://10.0.2.2:8080`).
4. Enter a URL or a search term. Search terms go to DuckDuckGo (HTML version).

## Building

Requirements: Android Studio (or Gradle 8.x), JDK 17+, Android SDK 36.

```bash
git clone https://github.com/danltech/dt-mini-browser-android.git
cd dt-mini-browser-android
# open in Android Studio, or:
./gradlew assembleRelease
```

## Privacy & security – please read

Like any server-side rendering browser, **the server sees everything you browse**, including page contents and anything you type into forms. Only use a server you run yourself or fully trust.

- The app currently allows **cleartext HTTP** (`usesCleartextTraffic=true`) to reach a server in your home network. If you expose your server to the internet, put it behind **HTTPS (reverse proxy)** and add authentication.
- The server stores cookies/sessions per client ID so you stay logged in.
- Bookmarks and history stay on your device (SharedPreferences, no backup).
- The app requests only `INTERNET` (and `WRITE_EXTERNAL_STORAGE` on Android 9 and below for downloads).

## Roadmap

- [ ] English UI / localization
- [ ] Authentication between app and server
- [ ] HTTPS-first server connection
- [ ] Better layout for complex pages
- [ ] Proper app name/icon

## Contributing

Issues and pull requests are welcome. Please open an issue first for larger changes.

## License

Licensed under the [Apache License 2.0](LICENSE) © DanlTech

The companion [server](https://github.com/danltech/dt-mini-browser-server) is licensed separately under the AGPL-3.0.

## Disclaimer

DT Mini Browser is an independent project. It is **not affiliated with, endorsed by, or sponsored by** Opera Software or any other browser vendor. All product names and trademarks mentioned belong to their respective owners. The concept of server-side rendering for lightweight browsing is a general technique used by several products.
