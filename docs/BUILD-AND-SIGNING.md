# 构建、安装、升级与签名备份

PicHarbor / cc_cece / com.cccece.picharbor / 1.0.1 (10001)，Android 10+ arm64。与 io.gmgc.probe 验证版拥有独立沙箱，首次重新登录。

## 构建与安装

Windows PowerShell、JDK 17、Android SDK 36、Python 3.13。设置 JAVA_HOME / ANDROID_HOME，Python 路径通过 BuildPython 参数提供。首次需要网络；项目不读取旧工作区。

```powershell
.\tools\build-release.ps1 -Verify -BuildPython 'C:/path/to/python.exe'
python tools/verify_engine.py
python tools/inspect_release.py
python tools/package-release.py
adb install -r dist/PicHarbor-1.0.1-arm64.apk
```

也可将 APK 复制到手机文件管理器安装，允许该来源安装应用。arm64 APK 不支持仅 x86 的模拟器。

## 签名

首次 release 创建 RSA-3072 固定密钥，别名 picharbor，位置 `%LOCALAPPDATA%\PicHarborBuild\signing\picharbor-release.jks`。同目录 password.dpapi 由当前 Windows 用户和机器 DPAPI 保护。密码不写入源码或明文命令行，经进程环境传给构建工具后清理。已存在密钥/密码缺少配对时停止，禁止静默更换身份。

DPAPI 文件不能在另一机器/另一 Windows 用户下直接解密，仅复制两个文件不是可靠异机备份。

## 可迁移的离线备份

先完成正式构建，然后在当前 Windows 用户下运行：

```powershell
.\tools\backup-signing.ps1 -Destination 'E:\PrivateBackup\PicHarbor-signing.p12'
```

终端安全输入至少 12 位新备份密码，导出相同证书和私钥的加密 PKCS12。p12 放离线安全位置，密码单独保管，禁止放进源码目录或聊天附件。

新机器恢复时，用 JDK keytool 将 PKCS12 导入上述 signing 目录的 JKS，保留别名 picharbor。用 PowerShell Read-Host -AsSecureString 取得目标密码，ConvertFrom-SecureString 写入 password.dpapi。keytool 使用 -srcstorepass:env / -deststorepass:env 等参数，避免明文历史。恢复后核对证书 SHA-256 与旧发布记录一致再升级。

## 升级

递增 versionCode，保持包名和签名证书。Room version=1，结构在 app/schemas；变更必须新增 Migration 并验证旧库升级，禁止 destructive fallback。设置与任务证据保留，验证版无需迁移。

正式包可同签名覆盖升级。debug 签名不能覆盖正式版；不要用卸载解决签名不一致，卸载会删除本地凭据与记录。云端媒体不会随卸载删除。
