# PicHarbor 1.0.2

本轮处理多账号、释放空间、手动任务启动、提交后的页面行为和底栏过渡五项反馈。

## 功能与修复

- 「我的 → 备份账号」保存多个账号并快速切换；添加账号取消时保留原账号。每个账号使用独立 Android Keystore 密钥加密凭据，分别保存备份范围和偏好。切换前取消调度并等待在途操作结束，备份凭证、任务和清理结果仍按账号隔离。移除一个账号不会清除其他账号的凭据，也不会删除云端照片。首次覆盖升级会验证迁移旧凭据，再移除旧加密副本。
- Android 14 用户发起传输的 NetworkRequest 默认带有 NOT_VPN。这台 K40 开启 VPN 时，系统接受了任务，但 CONNECTIVITY 条件始终未满足。现在移除默认 NOT_VPN 条件，检查底层 Wi-Fi，并独立唤醒每次手动提交；保留延时 Worker 兜底。执行器等待互斥锁，不再因上一执行器正在退出而丢失唤醒。
- 用户明确选中的文件不受自动备份文件夹、自动备份视频范围及同名跳过选项限制。排队的自动项目可转入优先手动任务，已有上传结果继续用于归类重试。暂停、恢复和历史仍保留。
- 提交手动任务留在当前页面，任务写入历史；应用内显示完成或错误提示，离开应用后由通知提示，并可前往任务记录。
- 每个完整页面独立拥有 Scaffold 和固定底栏。页面过渡不再收缩共享底栏、改变详情页内容区域；统一消费系统窗口边距，避免重复应用边距。
- 清理界面只将核验通过的容量显示为可释放。候选容量另行标明，零项通过时明确说明手机原件保留，不再显示等待确认的成功文案。导出诊断增加脱敏任务状态及核验原因统计。

## 清理核验依据

真机旧缓存的 155 项均未返回回收站时间戳，153 项有原始画质信息。旧实现因此将正常项目全部判断为未知。

用本轮创建的可丢弃 JPEG 对照服务器响应：正常项目的回收站子消息为空；移入回收站时出现正数时间戳；恢复后重新为空。测试照片已恢复，未永久删除云端内容。回收站操作只存在于单独安装的 androidTest APK，正式应用清理流程没有调用云端删除接口。

新版区分完整响应中的空可选子消息与缺失、畸形或未解析的信息。每次清理先重新完成全量云端同步，给结果关联独立快照标识；仅在完整快照成功且未超过五分钟时，才解释已确认的空回收站标记。同步失败立即废止旧快照的清理资格；缺少字段仍保留原件。

清理仍要求当前账号内容 SHA-1 查询匹配、本地 SHA-256 与备份凭证一致、云端上传状态为已完成、原始画质和文件大小明确、原件 HEAD 或限量 Range 可访问、本地文件未变化。不会完整回下载原件。用户确认与系统授权仍是实际删除的前提。

## 构建和诊断

正式版本：1.0.2，versionCode 10002，包名及正式签名不变，可覆盖 1.0.0 / 1.0.1。

```powershell
.\tools\build-release.ps1 -Verify
python tools/verify_engine.py
python tools/package-release.py
```

设备回归使用单独的 `diagnostic` 构建类型，沿用正式包名和签名以验证现有 Keystore 数据，不做代码压缩。生产 `release` 仍执行 R8 和资源压缩，不包含测试资源或测试入口。

```powershell
.\tools\build-release.ps1 -DeviceTests -DiagnosticsOnly
adb install -r app/build/outputs/apk/diagnostic/app-diagnostic.apk
adb install -r app/build/outputs/apk/androidTest/diagnostic/app-diagnostic-androidTest.apk
adb shell am instrument -w -e class com.cccece.picharbor.DeviceRegressionTest com.cccece.picharbor.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试会使用当前登录账号，创建明确命名的 `PicHarbor-disposable-*` 测试文件，并对自己的测试照片进行可逆的移入回收站及恢复。不会删除用户原件，测试文件保留。测试结束应重新安装正式 release APK；不要清除应用数据，否则会丢失设备上的凭据和任务记录。

## 验收边界

设备为用户授权调试的 Redmi K40 / M2012K11AC、Android 14（API 34）、类原生系统、已 Root，日期 2026-10-18。实现本身不依赖 Root，Root 仅用于读取脱敏诊断结果。

1.0.2 为开发迭代，相关修复随 1.0.3 一并交付；自动化结果、签名与正式产物哈希见 `release-1.0.3-checks.json`。本次仅有一个真实 Google 账号可用；已验证旧凭据迁移、已保存账号复用、独立凭据的加密增删和账号设置隔离。两个不同真实账号之间的 Google 登录与切换仍需用户补充验收，不据此宣称已实测。
