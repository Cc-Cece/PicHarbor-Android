<div align="center">

# PicHarbor Android

Native Android client for Google Photos backup with embedded Python.

[![Android](https://img.shields.io/badge/Android-10.0+_(API_29+)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Python](https://img.shields.io/badge/Python-3.13_(Chaquopy)-3776AB?logo=python&logoColor=white)](https://chaquo.com/chaquopy/)
[![Release](https://img.shields.io/github/v/release/Cc-Cece/PicHarbor-Android?color=007AFF&logo=github)](https://github.com/Cc-Cece/PicHarbor-Android/releases)
[![License: GPL--3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

<p align="center">
  <b>English</b> | <a href="README_ZH.md">简体中文</a>
</p>

<p align="center">
  <a href="https://github.com/Cc-Cece/PicHarbor-Android">GitHub Repository</a> · <a href="https://github.com/Cc-Cece/PicHarbor-Android/releases">Releases</a>
</p>

<p align="center">
  Related: <a href="https://github.com/Cc-Cece/PicHarbor">Desktop</a> · <a href="https://github.com/Cc-Cece/PicHarbor-iOS">iOS</a>
</p>

</div>

---

PicHarbor Android is a native Google Photos backup application for Android 10+. Built with Jetpack Compose, it embeds a Python 3.13 runtime and the `gpmc` library via Chaquopy to handle Google Photos uploads and sync directly on the device.

> [!NOTE]
> Requires Android 10 (API 29) or newer. Targets 64-bit ARM devices (`arm64-v8a`).

## Features

- **Media & Photo Access**: Scans photos, videos, and Motion Photos via MediaStore, with support for custom folders and SAF document tree selection.
- **Embedded Protocol Client**: Embeds Python 3.13 and `gpmc` via Chaquopy for on-device communication with Google Photos.
- **Credential Security & Multi-Account**: Accounts and session tokens are encrypted using dedicated AES-GCM keys, with fast multi-account switching.
- **Material 3 Interface**: Pure Jetpack Compose UI supporting dynamic color theming, dark mode, and media previews.

## Build from Source

### Requirements

- JDK 17 (configured via `JAVA_HOME`)
- Android SDK (API 36, configured via `ANDROID_HOME`)
- Local Python 3.13 (used by Chaquopy during build)

### Build Commands

Run Gradle commands from the repository root:

```powershell
# Build diagnostic / debug APK
.\gradlew.bat assembleDiagnostic

# Or build and sign a Release APK using the release script
powershell -File tools/build-release.ps1
```

Generated APKs are located under `app/build/outputs/apk/`.

## Project Structure

- `app/src/main/java/`: Jetpack Compose UI, Room database, WorkManager schedulers, and media management logic
- `app/src/main/python/`: Embedded `gpmc` protocol library and `blackboxprotobuf` modules
- `app/src/main/assets/`: Engine fingerprint baselines and licensing records
- `tools/`: Build, signing, and package inspection scripts

## Acknowledgements

PicHarbor Android references [gpmc](https://github.com/xob0t/gpmc) and [blackboxprotobuf](https://github.com/nccgroup/blackboxprotobuf).

The author opposes using this project for marketing or unauthorized promotion. Please do not falsely claim official endorsement.
