# PicHarbor 1.0.4

作者 **cc_cece** · 包名 `com.cccece.picharbor` · Android 10+ / arm64。

原生 Compose 界面以保留的 `demo/` 为设计蓝本，提供「照片、备份、我的」。完整本地 gpmc 连接 Google 相册，无桌面项目路径依赖；可与旧验证版共存，首次重新登录。

## 使用

1. 安装 `dist/PicHarbor-1.0.4-arm64.apk`，允许需要的媒体与通知权限。可直接覆盖旧正式版，保留现有账号与记录。
2. 登录 Google，在 Google 页面完成两步验证和必要确认。可在「我的 → 备份账号」添加、切换或移除保存在设备上的账号。
3. 选择备份文件夹并确认开启自动备份。默认相机、Wi-Fi、视频，也可手动选择文件。
4. 备份页查看进度、暂停/继续；任务记录重试失败或归类。
5. 「我的 → 释放手机空间」先核验，再由用户和系统确认。未知状态保留原件。

备份完成与清理资格使用独立证据。清理不完整回下载原件，不调用云端删除。系统和厂商限制可能延后自动任务，不保证永久常驻或分钟级准点。

本版增加检查耗时详情、独立的快速备份状态查询及 1～20 项检查上限；本地读取单独限流，支持有限重试和自动降速。默认仍为 2 项，完整清理核验没有减少步骤。依据与验收边界见 [更新说明](docs/UPDATE-1.0.4.md)。

## 构建与交付

Windows PowerShell、JDK 17、Android SDK 36、Python 3.13。设置 JAVA_HOME / ANDROID_HOME；首次需要访问依赖仓库。

```powershell
.\tools\build-release.ps1 -Verify -BuildPython 'C:/path/to/python313/python.exe'
python tools/verify_engine.py
python tools/inspect_release.py
python tools/package-release.py
```

脚本首次创建固定正式签名，后续复用。密钥在工作区之外，密码由 DPAPI 保护，仅经进程环境传入。详见 [构建、安装、升级与签名备份](docs/BUILD-AND-SIGNING.md)。源码包不包含私钥。

开发 debug 包可用 `gradlew.bat :app:assembleDebug -PbuildPython=...` 构建，但不能覆盖正式签名安装。依赖见 `app/gradle.lockfile`、`app/requirements.lock`。

## 源码

- `app/src/main/java/com/cccece/picharbor/`：媒体、Room、设置、账号、任务、调度、清理和原生界面。
- `app/src/main/python/gpmc/`：完整引擎；新增业务集中在 `mobile.py`。
- `picharbor_engine.py`：JSON 薄入口，直接调用 Python，不启动子进程。
- `app/schemas/`：Room 结构；后续变更必须显式迁移。
- `docs/engine-baseline.json`：首次复制哈希；`engine-extension.json`：独立扩展清单。
- `reference/`：桌面桥与原验证版参考，正式构建不作入口。
- `demo/`：设计参考，示例图片与模拟交互不进入 APK。
- `tests/` 与 `app/src/test/`：离线边界测试，不替代真机证据。

[本次更新与验收](docs/UPDATE-1.0.4.md) · [架构与功能](docs/ARCHITECTURE.md) · [引擎改动](docs/ENGINE-CHANGES.md) · [隐私](docs/PRIVACY.md) · [1.0.0 验收](docs/RELEASE-VALIDATION.md) · [来源与许可](NOTICE.md)。旧验证报告单独保留，其通过结果不自动覆盖新增流程。
