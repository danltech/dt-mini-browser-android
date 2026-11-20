# DT Mini Browser – Android Client

**A tiny, dependency-free Android browser that lets a server do the heavy lifting.**

The app talks to a rendering server ([`dt-mini-browser-server`](https://github.com/danltech/dt-mini-browser-server)). The server loads pages in a real Chromium (JavaScript and CSS included) and sends back a compact display list; the app only draws it.

> Status: **Beta 1.0**. Expect rough edges.

## Why?

- Light on the device: no web engine in the app, tiny APK
- Runs on old phones: `minSdk 16` (Android 4.1+)
- Zero dependencies: plain Java, no AndroidX, no Kotlin
- Ads/trackers are filtered on the server

## Features

Tabs, bookmarks and history (stored locally), form input, downloads via the server, certificate info for HTTPS, setup by QR code, default-browser support (`http`/`https`), debug overlay. Languages: English, Deutsch, Español, Français, Italiano, Türkçe, Русский, 日本語, 简体中文.

## Getting started

1. Set up the server first: see [dt-mini-browser-server](https://github.com/danltech/dt-mini-browser-server).
2. Install the APK from *Releases* (or build it yourself).
3. In the app: menu → **Server address**, then enter e.g. `https://192.168.1.50:8080` (emulator: `http://10.0.2.2:8080`), or scan the setup QR code / paste `setup.txt`.
4. Enter a URL or search term (search uses DuckDuckGo).

## Building

Requires JDK 17+ and Android SDK 36 (Android Studio or Gradle).

```bash
git clone https://github.com/danltech/dt-mini-browser-android.git
cd dt-mini-browser-android
./gradlew assembleRelease
```

Release signing keys (`*.jks`, `*.keystore`) are git-ignored; use your own.

## Privacy & security

- **The server sees everything you browse**, including form input. Use only a server you run yourself or fully trust.
- Cleartext HTTP is allowed to reach servers in a home network. Prefer HTTPS with a token and a VPN or reverse proxy when going over the internet.
- Bookmarks and history stay on the device (no backup). The app requests only `INTERNET` (and `WRITE_EXTERNAL_STORAGE` up to Android 9 for downloads).

## Translations

Strings are in `app/src/main/res/values-*/strings.xml`. Pull requests for more languages are welcome.

## License

Apache-2.0, see [LICENSE](LICENSE). The server is licensed separately (AGPL-3.0).

DT Mini Browser is an independent project and is not affiliated with, endorsed by, or sponsored by Opera Software or any other browser vendor.
