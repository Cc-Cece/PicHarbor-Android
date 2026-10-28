<div align="center">

# PicHarbor Android

面向 Android 的 Google 相册原生备份客户端。

[![Android](https://img.shields.io/badge/Android-10.0+_(API_29+)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Python](https://img.shields.io/badge/Python-3.13_(Chaquopy)-3776AB?logo=python&logoColor=white)](https://chaquo.com/chaquopy/)
[![Release](https://img.shields.io/github/v/release/Cc-Cece/PicHarbor-Android?color=007AFF&logo=github)](https://github.com/Cc-Cece/PicHarbor-Android/releases)
[![License: GPL--3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

<p align="center">
  <a href="README.md">English</a> | <b>简体中文</b>
</p>

<p align="center">
  <a href="https://github.com/Cc-Cece/PicHarbor-Android">GitHub 仓库</a> · <a href="https://github.com/Cc-Cece/PicHarbor-Android/releases">发布版本</a>
</p>

<p align="center">
  相关项目：<a href="https://github.com/Cc-Cece/PicHarbor">Desktop</a> · <a href="https://github.com/Cc-Cece/PicHarbor-iOS">iOS</a>
</p>

</div>

---

PicHarbor Android 是一款面向 Android 10+ 的 Google 相册原生备份应用。基于 Jetpack Compose 构建，通过 Chaquopy 内嵌 Python 3.13 运行环境与 `gpmc` 协议库，直接在设备端进行 Google 相册备份与管理。

> [!NOTE]
> 运行要求：Android 10（API 29）或更高版本，仅支持 64 位设备（`arm64-v8a`）。

## 功能特性

- **相册与媒体访问**：通过 MediaStore 扫描照片、视频与动态照片（Motion Photo），支持自定义相册文件夹与 SAF 目录树授权。
- **内嵌协议客户端**：通过 Chaquopy 内嵌 Python 3.13 与 `gpmc` ，在手机本地完成 Google 相册协议交互。
- **凭据安全与多账号**：Google 账号会话与凭据独立 AES-GCM 密钥加密存储，支持多账号快速切换。
- **Material 3 界面**：采用纯 Jetpack Compose 开发，支持动态取色、深色模式与媒体全屏预览。

## 从源码构建

### 环境要求

- JDK 17（配置 `JAVA_HOME`）
- Android SDK（API 36，配置 `ANDROID_HOME`）
- 本地 Python 3.13（用于 Chaquopy 构建依赖处理）

### 编译命令

在仓库根目录下执行 Gradle 命令：

```powershell
# 编译诊断版 / 调试版 APK
.\gradlew.bat assembleDiagnostic

# 或使用发布脚本构建并签名 Release APK
powershell -File tools/build-release.ps1
```

编译产物位于 `app/build/outputs/apk/` 目录下。

## 项目结构

- `app/src/main/java/`：Jetpack Compose 界面、Room 数据库、WorkManager 调度与媒体管理逻辑
- `app/src/main/python/`：内嵌的 `gpmc` 协议库与 `blackboxprotobuf` 模块
- `app/src/main/assets/`：引擎指纹基线与许可声明文件
- `tools/`：构建、签名与安装包检查脚本

## 致谢

PicHarbor Android 参考了 [gpmc](https://github.com/xob0t/gpmc) 与 [blackboxprotobuf](https://github.com/nccgroup/blackboxprotobuf) 。

作者反对利用本项目进行引流或商业营销。请勿冒充官方或虚称作者背书。
