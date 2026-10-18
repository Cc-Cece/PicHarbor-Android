# 固定依赖

| 组件 | 版本 / 来源 |
| --- | --- |
| Gradle | 8.13，官方 wrapper |
| Android Gradle Plugin | 8.12.2，Google Maven |
| Kotlin / Compose compiler plugin | 2.2.10，Maven Central / Plugin Portal |
| Chaquopy | 17.1.0，Maven Central |
| Python Android runtime | 3.13，由 Chaquopy 固定 runtime 依赖解析 |
| JDK | 17 |
| Android compile / target SDK | 36 |
| Android minimum SDK / ABI | 29 / arm64-v8a |
| Compose BOM | 2025.08.00 |
| AndroidX Activity | 1.10.1 |
| AndroidX Lifecycle | 2.9.2 |
| Kotlin Coroutines | 1.10.2 |
| JUnit | 4.13.2，测试依赖 |
| requests / urllib3 | 2.32.5 / 2.5.0 |
| certifi / charset-normalizer / idna | 2025.8.3 / 3.4.3 / 3.10 |
| rich / markdown-it-py / mdurl | 14.1.0 / 4.0.0 / 0.1.2 |
| Pygments / six | 2.19.2 / 1.17.0 |
| gpmc / blackboxprotobuf | 完整本地源码，以 engine-baseline.json 哈希固定 |

Android 传递版本见 `app/gradle.lockfile`；Python 全部外部运行依赖见 `app/requirements.lock`。Python 包从 PyPI 和 Chaquopy 官方索引解析，避免另装第三方同名 gpmc/blackboxprotobuf 覆盖本地源码。

官方资料：

- https://chaquo.com/chaquopy/doc/current/android.html
- https://chaquo.com/chaquopy/doc/current/versions.html
- https://developer.android.com/build/releases/past-releases/agp-8-12-0-release-notes
- https://developers.google.com/identity/protocols/oauth2/policies
- https://support.google.com/photos/answer/6220791
- https://developer.android.com/privacy-and-security/keystore

正式版新增固定依赖：Material 3 1.4.0、Material icons 1.7.8、Room 2.7.2、DataStore 1.1.7、WorkManager 2.10.3、DocumentFile 1.0.1、Coil 2.7.0、Media3 1.8.0。完整传递依赖以正式构建生成的锁文件为准。
