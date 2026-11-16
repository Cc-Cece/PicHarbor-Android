# PicHarbor Android 功能清单（移动端统一盘点）

日期：2026-11-13（分支 `unification/overnight-20261113`，基线 `baseline/mobile-unification-20261112`）。
目的：阶段一盘点。逐项记录功能、当前实现位置、稳定/实验、平台依赖。
依据：仓库代码与 `docs/MOBILE-UNIFICATION-CHARTER.md`、`docs/PROJECT-STATUS-2026-11-12.md`、
`docs/diagnostics/README.md`、`docs/diagnostics/IMPLEMENTATION.md`、`docs/diagnostics/RUNBOOK.md`、
`docs/releases/1.0.9-beta.5.md`。与 iOS 侧确认的决定冲突时，以 Yuto 已确认的文档为准。

> **已确认的范围修订（2026-11-13，Yuto 决定；源自 iOS `docs/REQUIREMENTS.md` 2026-10-19 修订）：**
> 本机清理交由 Google 相册官方“释放空间”，取消 PicHarbor 自建本机清理功能；
> PicHarbor 只保留备份确认和官方清理指引，不再扫描或删除本机资产。
> 因此下表把自建清理整批标注为 **已移除**（本分支已删除删除执行路径与调用入口，
> 仅保留备份确认逻辑）；`harbor_core.decide_cleanup` 的语义同步改为
> “云端确认证据是否足以指引官方清理”，不再是“可以删除本机原件”的放行信号。

## 约定

- **稳定**：已有实现、随 1.0.9-beta.5 或更早版本发布/验证过。
- **实验**：实现存在但未经实机/真实账号完整验证，或明确标注为测试版能力。
- **平台依赖**：媒体 = MediaStore/SAF；权限 = 运行时权限；WorkManager = 后台调度；
  安全存储 = Keystore/EncryptedSharedPreferences；Chaquopy = 内嵌 Python；
  系统删除 = MediaStore 删除授权（已移除的清理功能曾依赖）。

## 1. 备份主流程（普通照片）

| 功能 | 实现位置 | 状态 | 平台依赖 |
| --- | --- | --- | --- |
| 媒体扫描与索引（MediaStore + SAF 树） | `MediaRepository.scan/scanVolume/scanTree`、`Database.MediaEntry` | 稳定 | 媒体、权限 |
| 手动选择文件导入 | `MediaRepository.importUris`、`HarborViewModel.importFiles` | 稳定 | 媒体、权限 |
| 任务入队、去重（按内容记录匹配） | `BackupRepository.enqueue`、`Database.BackupPolicy.matches` | 稳定 | Room |
| 普通照片经共享核心 | `HarborCore`（`harbor_core.dispatch_json` 适配器）+ `BackupRepository.processSingleWithCore`；旧直接调用路径已在第二阶段删除，无 fallback（核心不可用时项目留队重试） | 实验（核心 `harbor_core.py` 已同步，见第 7 节；未实机验证） | Chaquopy |
| 哈希、云端查重、上传、提交、相册归类 | `app/src/main/python/harbor_android.py: MobileService.process`（经 `Engine.call("process")`） | 稳定 | Chaquopy、网络 |
| 上传进度回调与并发名额 | `EngineCallback`、`BackupRepository.run`（Semaphore） | 稳定 | 协程 |
| 云端确认后才标记完成 | `BackupRepository.process`：commit 返回 remote_key 后才 `done`；`reconcile()` 定期核对云端缺失 | 稳定 | 网络 |
| 暂停 / 取消 / 恢复 | `BackupRepository.pause/stopAndWait/resume/retry/end`、`Database.recoverJobs/recoverItems` | 稳定 | Room、WorkManager |
| 进程重启恢复（核心任务） | `BackupRepository.recoverCoreTasks`：`HarborCore.store_load`，`uploading/submitting/confirming` → 核心 `interrupted`，Room 侧已由 `recoverItems` 置回 `queued` | 实验（待核心同步） | Chaquopy、Room |
| 失败重试（退避） | 传输内重试在 `harbor_android.py`；核心 `retry_delay` 建议值经 `HarborCore.retryDelay` 取回并记 `retry.scheduled` 事件；任务级重试走 `BackupRepository.retry` + 调度器 | 稳定（传输内）；实验（核心建议值） | Chaquopy |
| Wi-Fi / 移动流量 / 充电条件 | `BackupRepository.onWifi`、`Preferences`（wifi/mobile/charging）、`Scheduler` 约束 | 稳定 | 网络、WorkManager |
| 后台调度（WorkManager + UIDT 兜底） | `Scheduler`（`BackupWorker`、`TransferJob`、`RestoreReceiver`、`PauseReceiver`） | 稳定 | WorkManager |
| 相册归类（按文件夹/自定义/已有相册） | `Engine.call("albums")`、`harbor_android.py` 相册分支、`HarborViewModel.album(s)` | 稳定 | Chaquopy |
| 画质（original/saver/quota） | `Preferences.quality`、`harbor_android.py` commit 参数 | 稳定 | Chaquopy |
| 同名跳过（不计入清理资格） | `harbor_android.py` `same_name` 分支 → `filename_skipped` | 稳定 | Chaquopy |
| 视频备份 | 与照片同一核心流程（`media_kind=video`，asset 带 `duration_seconds`、`size`；`BackupRepository.processSingleWithCore`；原生传输与 WorkManager 适配保留） | 实验（核心 `harbor_core.py` 尚未同步，见第 7 节；未实机验证） | 媒体 |
| Live Photo / 多资源 | `LivePhotoPairing`（同名干 + 同文件夹 + 5 秒内配对；Motion Photo 单文件 XMP 检测）；一个媒体项目一个核心任务（`media_kind=live_photo`，`asset.resources` 逐资源 `decide_prepare`）；默认原始照片配对联合提交，已有设置不被升级覆盖；核心不认 `live_photo` 时分步上传（每资源一个核心任务）；未验证的 Live Photo 只备份、不清理 | 实验（未实机验证） | 媒体、Chaquopy |
| 批量任务 | `BackupRepository.pauseJobs/resumeJobs/cancelJobs`：批量创建（`enqueue`）、暂停/恢复/取消镜像到核心（`batch_pause`/`batch_resume`，入参 `{task_ids, tasks}`，逐项结果解析；核心未提供时逐任务 `transition` 回退）；暂停/恢复即时镜像，无“下次进入重同步”缺口 | 实验（核心新 action 待同步） | Chaquopy、Room、WorkManager |
| 相册范围 | `ScopePolicy.evaluate`：范围走核心 `evaluate_scope`（scope 含 mode/albums/dateEnabled/from/to/photos/videos/ignoreSmallImages/minImageKb/filterCache，两端同义）；未知版本 scope 报错不退回全量（整批入队拒绝）；核心未提供时本地 `includes()` 回退 | 实验（核心新 action 待同步） | Chaquopy |
| 资源复用 / 去重 | `decide_prepare`（`remote_key` 命中即 `content_exists` 跳过上传）是唯一去重决策；传输不再二次推导（`transportItem` 接 `lookup_fresh`/`lookup_remote_key`，`harbor_android.py` 跳过重复 `find_remote_media_by_hash`） | 实验（核心已同步；Python 适配器哈希已更新） | Chaquopy |
| 代理 | `ProxyPolicy` + `CredentialStore` 代理凭据（Keystore AES-GCM）：配置/选择/路由走核心 `validate_proxy`（显式代理参数，默认关；凭据不进核心、不进日志）；TLS 校验保留（`session.verify=True`）；`Engine.connect` 透传代理 URL | 实验（核心新 action 待同步；未实机验证） | 安全存储、Chaquopy |
| 电脑直连 LiveLink | `LiveLink` 门控：`livelink_handshake`（校验 scope2/catalog2 能力）与 `livelink_scope`（范围校验，`requires_confirmation:true`）；任何传输均需接收方确认；Live Photo 按一个媒体项目确认；失败/未知时关闭（不传输）。Android 尚无对端传输实现，门控通过后才可交由配对传输 | 实验（核心新 action 待同步；无对端传输实现） | Chaquopy |
| 备份记录与统计 | `Database.BackupRecord/BackupJob/BackupItem/JobStatistics`、`HarborViewModel.report` | 稳定 | Room |

## 2. 账号与认证

| 功能 | 实现位置 | 状态 | 平台依赖 |
| --- | --- | --- | --- |
| Google 登录（WebView OAuth） | `LoginActivity` | 稳定 | WebView、网络 |
| 凭据安全存储（Keystore 加密） | `CredentialStore`（`picharbor.account.<sha256>` 别名；旧版迁移 `migrateLegacy`） | 稳定 | 安全存储 |
| 多账号切换 / 移除 | `AppGraph.activate/selectAccount/removeAccount`、`HarborViewModel.selectAccount/removeAccount` | 稳定 | 安全存储、Room |
| 令牌刷新与账号一致性校验 | `Engine.connect`、`AppGraph.ensureAccount`、`harbor_android.py: MobileService.connect` | 稳定 | Chaquopy、网络 |
| 账号头像/展示名（可选） | `AppGraph.refreshProfile`、`Engine.call("account_profile")` | 稳定 | 网络 |

## 3. 清理 / 释放空间 —— 已移除（见文首决定）

| 功能 | 原实现位置 | 状态 | 说明 |
| --- | --- | --- | --- |
| 自建清理扫描与核验 | 原 `CleanupRepository.runScan/verify/enqueue`、`CleanupWorker` | **已移除** | 不再扫描待删资产；核验逻辑仅以证据函数形式保留在 Python 侧（见下） |
| 自建清理策略与风险确认 | 原 `CleanupPolicy`、`CleanupPolicyUi` | **已移除** | 含 `RISK_CONFIRMATION_REQUIRED` 确认语流程一并移除 |
| 系统删除执行（MediaStore 删除授权 / SAF 删除 / Android 10 旧路径） | 原 `CleanupRepository.systemRequest/finishDelete/deleteLegacy`、`MainActivity.requestCleanup` | **已移除** | 不留删除后门；用户自选单项“从手机删除”（`MainActivity.requestLocalDelete`，系统回收站授权）与批量释放空间无关，予以保留，见第 6 节 |
| 清理定时/后台触发与通知 | 原 `CleanupWorker`、`CleanupNotifications`、`CleanupRepository.schedule/restorePending` | **已移除** | 含 `AndroidManifest.xml` 中的 `CleanupCancelReceiver` 注册 |
| 清理证据持久化 | 原 `Database.CleanupRecord/CleanupScan/CleanupScanItem` | **已移除** | 三张表随 Room v5 迁移（`MIGRATION_4_5`）删除；备份记录、任务与项目表不受影响 |
| 云端确认证据（保留，供官方清理指引使用） | `harbor_android.py: verify_cleanup`（Python 侧，哈希锁定，`engine-extension.json` 校验，不可擅自改动）+ 核心 `harbor_core.decide_cleanup`；逐项证据源已接：`BackupRepository.reconcile()` 产出 `lastCleanupEvidence`（`[{task_id, cloud_confirmed, confirmed_at}]`），`cleanupGuidanceEligible(itemId)` 要求逐项证据 + 核心同意才放行逐项，无证据时只做页级指引 | 稳定（证据函数）；实验（核心接入） | Chaquopy；证据标准：新鲜完整快照（协议层快照有效性 300 秒）、非回收站、原始画质、状态 100、原件可访问；核心 `decide_cleanup` 的“是否可指引官方清理”证据新鲜窗是冻结接口的 3600 秒，两者不要混淆 |
| 官方释放空间指引 | 新增 `FreeSpaceGuide`：优先 `ACTION_VIEW` 打开已验证 Universal Link `https://photos.google.com/link/freeupspace`；失败回退：显式启动 Google 相册包 `com.google.android.apps.photos` → 未安装跳 Play 商店详情页 → 再失败给手动步骤文案；入口在 `FreeSpaceScreen`（设置/高级设置、个人页卡片、详情页） | 实验（Android 深链未实机验证，见下） | Intent、包管理器 |

**深链验证状态（2026-11-13 核查）：** `https://photos.google.com/link/freeupspace`
已在 iOS 0.2.12 验证（Google 生产 iOS AASA 含该路径、用户 iPhone 实测直达目标页；
6 个猜测的 `googlephotos://` 路径已失败并移除，不再使用）。Android 侧是否同样直达、
Google 相册未安装/多账号时回退，均未实机验证，需实测后定。`FreeSpaceGuide` 因此
按“链接 → 显式启动相册包 → Play 商店 → 手动步骤”顺序回退，不编造其他深链；
打开链接不代表清理已完成，PicHarbor 不因此改变任务状态。

## 4. 诊断日志

| 功能 | 实现位置 | 状态 | 平台依赖 |
| --- | --- | --- | --- |
| 常开异步有界记录器 | `Diagnostics`（`noBackupFilesDir/diagnostics/`） | 稳定 | 文件系统 |
| 保留 14 天 / 64 MiB 滚动 | `Diagnostics.MAX_BYTES/KEEP_MS/prune` | 稳定 | 文件系统 |
| 事件白名单与脱敏（文件名仅 basename、禁完整路径；禁内容哈希、凭据与账号标识；不记媒体本体与明文秘密） | `DiagnosticFields.clean/text/error`、`DiagnosticContext` | 稳定 | — |
| Python 侧埋点与安全 sink | `app/src/main/python/harbor_diagnostics.py`（哈希锁定） | 稳定 | Chaquopy |
| 导出 ZIP（JSONL + readable.log + manifest/coverage/environment/summary） | `Diagnostics.export`、设置导出入口 `DiagnosticExportDialog` | 稳定 | 存储访问框架 |
| 核心日志事件 | `HarborCore.logEvent` → `harbor_core.log_event`（经 `DiagnosticFields.clean` 脱敏后转发，best-effort，不阻塞业务） | 实验（待核心同步） | Chaquopy |
| 独立无进展观察器 / 退出信息摘要 / 崩溃应急写入 | `Diagnostics.inspect`、退出信息补采、未捕获异常 handler | 稳定 | 系统服务 |
| 已知缺口（按 `docs/diagnostics/IMPLEMENTATION.md`） | Python/native 栈、真实 socket 发送量、DNS/TLS 分段计时不可用；事故区/持久 revision 审计/自动根因分类未实现 | 实验 | — |

日志与发布口径以 `docs/diagnostics/README.md`、`IMPLEMENTATION.md`、`RUNBOOK.md`、
`docs/releases/1.0.9-beta.5.md` 为准：日志始终开启、无关闭/保留配置入口；导出默认全部、
可选日期区间；固定签名重发证书 SHA-256 见发布文档，私钥与密码不入库。

## 5. UI（原生 Compose，分别维护，不承载业务决策）

| 功能 | 实现位置 | 状态 | 平台依赖 |
| --- | --- | --- | --- |
| 照片/备份/我的三栏 + 任务记录/详情 | `HarborUi`、`BackupListUi`、`ResultListsUi.JobDetailsScreen`、`DetailUi`（含视频播放） | 稳定 | Compose、ExoPlayer、Coil |
| 设置/文件夹/网络/高级/历史 | `SettingsUi` | 稳定 | Compose |
| 底部表单（账号/画质/相册/删除本地等） | `SheetsUi` | 稳定 | Compose |
| 顶部进行中状态条 | `TopStatusUi` | 稳定 | Compose |
| 主题/动效 | `Theme`、`Motion` | 稳定 | Compose |

## 6. 已移除的删除能力（第二阶段决定，与“不自建删除”一致）

| 功能 | 原实现位置 | 状态 | 说明 |
| --- | --- | --- | --- |
| 用户自选单项从手机删除 | 原 `MainActivity.requestLocalDelete/deleteSafLocals`、`SheetsUi` `delete-local` 表单、`DetailUi` 删除按钮、`MediaRepository.deleteUri` | **已移除** | 2026-11-13 第二阶段决定：与“不自建删除”一致，连单项本机删除也移除；释放空间只走 Google 相册官方功能（见第 3 节） |

## 7. 共享核心接入状态（本分支）

- 适配器：`app/src/main/java/com/cccece/picharbor/HarborCore.kt`，只做 JSON 编解码与
  `harbor_core.dispatch_json(action, payload_str)` 调用；业务决策不在 Kotlin 侧重复。
  核心不可用时返回 `{"ok": false, "error": "ENGINE_ERROR"}`，调用方回退旧流程。
- 状态字符串与核心一致（见 `HarborCore` 常量）；本地 Room 投影映射：
  核心 `dedup_hit` → 本地 `exists`，`done` → `done`，`failed` → `failed`，
  `paused`/`waiting_network`/`interrupted` → `queued`（可重试），`cancelled` → `cancelled`。
  错误文案复用 `Engine.kt` 底部 `Messages.error(code)`。
- **已同步：** `app/src/main/python/gpmc/harbor_core.py` 已随 `tools/sync_gpmc.py`
  同步并锁定（见 `gpmc.lock.json`，`tools/sync_gpmc.py --check` 通过），
  阶段一的 9 个 action 可用；第二阶段新增的 6 个 action 尚未进入中央核心
  快照，见下条。
- 第二阶段新 action（`batch_pause`/`batch_resume`、`evaluate_scope`、
  `validate_proxy`、`livelink_handshake`/`livelink_scope`、`migrate_legacy`
  路由）同样尚未进入中央核心快照：Android 侧已按约定名调用，遇
  `UNKNOWN_OPERATION` 时走文档化的本地回退（批量逐任务 `transition`、
  范围本地 `includes()`、代理本地形状校验、LiveLink 关闭拒绝、迁移本地同义
  映射），不崩溃、不伪造证据、不退回全量。`migrate_legacy` 另有说明：核心函数
  已存在但未接入 `dispatch`，因此目前走本地映射。
- 旧业务核心路径已删除（第二阶段）：`processLegacy` 改为纯传输适配器
  `transportItem`，`process()` 不再回退旧路径；核心不可用时项目留队重试。
  仅剩共享核心 + 原生 UI + 平台适配器。
- 桌面端不在本次移动端统一范围内。

## 8. 构建与发布

| 事项 | 现状 |
| --- | --- |
| applicationId / 签名 | 未改；沿用 beta.5 固定签名密钥，私钥与密码不入库（`docs/SIGNING.md`） |
| gpmc 快照 | `tools/sync_gpmc.py` + `gpmc.lock.json` 内容锁定；`tools/verify_engine.py` 在 preBuild 离线校验 |
| Python 适配器哈希锁定 | `harbor_android.py` 等由 `app/src/main/assets/engine-extension.json` 锁定，本次未改动 |
| 本地验证 | 无 Java/Kotlin 编译环境，Kotlin 未本地编译；Python 侧见汇报验证结果；CI 验证待办 |
