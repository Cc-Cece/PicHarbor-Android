# 移动端统一任务记录

总体方向见 [任务书](MOBILE-UNIFICATION-CHARTER.md)。每完成一项，记录范围、实现、验证与遗留事项。

最新交接入口：[2026-11-12 计划、同步与分支总结](PROJECT-STATUS-2026-11-12.md)，包含推荐开发分支和 iOS 最终 CI 的剩余问题。

## GPMC-001：统一 gpmc 维护源并切换三端

日期：2026-11-12。核心整合与三端源码切换已完成，本地验证通过；后续原生 CI、审核与合并结果见 GPMC-002。真实账户和手机验收另行进行。

- 唯一维护源：`../gpmc`（`Cc-Cece/gpmc`）。
- 范围：整合电脑、iOS、安卓内部的通用协议改进，三端同时接入同一份内容锁定的源码快照。
- 方式：共享核心在独立库修改；各端运行统一同步工具更新副本，再由构建脚本离线校验。平台专属业务保持各端所有权。
- 已落地：上传计划校验、上传/相册进度、分阶段协议、Live Photo 公共 wire、请求预算取消、原件访问证据、错误分类及构建来源锁。
- 维护入口：[gpmc 维护说明](GPMC-MAINTENANCE.md)。
- 边界：未进行整个移动端业务迁移，未改变应用身份、签名或用户数据；本项不包含发布新版或真实账户云端验收。
- 后续记录：详细执行结果和未执行的验证以中央整合记录为准，不重复维护不同结论。
- 本地检查摘要：中央库 28 项、电脑 Python 11 项、安卓 JVM 24 项及 Python 诊断 4 项通过；iOS 72 项中 71 项通过、1 项环境跳过。安卓固定签名 Release 构建与电脑完整解决方案编译通过。
- 交付状态：代码已进入各仓库的提交、审核与合并流程，未发布应用版本；目标与结果以 GPMC-002 为准。

## GPMC-002：审核、提交、限定范围合并与构建完善

日期：2026-11-12。用户明确仅合并本次 gpmc，保留原有功能 PR 的独立范围。

- 中央 gpmc 目标：`main`。
- 安卓目标：`feat/android-diagnostics`。
- iOS 与电脑目标：各自的 `feat/paired-backup-scope`。
- 构建完善：核心跨系统 Python 检查与发行包检查；三端构建校验；iOS PR 的模拟器和无签名 IPA；电脑端显式 Python 环境；安卓无密钥验证构建及与发布分离的签名预览构建。
- 审核修复：源码包包含依赖锁，ZIP 桥按归一化文本比较，公开 Release 不再被手动测试构建自动覆盖。
- PR：[中央库 #1](https://github.com/Cc-Cece/gpmc/pull/1)、[安卓 #3](https://github.com/Cc-Cece/PicHarbor-Android/pull/3)、[iOS #2](https://github.com/Cc-Cece/PicHarbor-iOS/pull/2)、[电脑 #13](https://github.com/Cc-Cece/PicHarbor/pull/13)。
- 审核发现、最终合并提交和 CI 结果统一保存在[中央完成记录](https://github.com/Cc-Cece/gpmc/blob/main/INTEGRATION-LOG.md)，避免多个仓库维护不一致的结论。
- 安卓公开 beta.5 的 APK 校验值已核对，未被测试构建覆盖；固定发布签名继续校验。

### 安卓构建入口

PR 自动执行不需要发布密钥的验证任务。手动签名预览只生成 GitHub Actions 构建产物：

```sh
gh workflow run release.yml --repo Cc-Cece/PicHarbor-Android --ref feat/android-diagnostics -f publish=false
```

在对应运行的 Artifacts 中获取 `android-signed-<提交 SHA>`；它使用既有固定发布密钥。公开发布需明确选择 `publish=true`，应用版本与标签必须一致，已有 Release 不允许覆盖。

本次新构建流程位于上文指定的功能分支，未将其他功能并入客户端 main。后续将这些功能正式合入 main 时再同步相应入口。

## 夜间重构（2026-11-13，unification/overnight-20261113，未合入 main）

范围：Android 移动端统一。普通照片备份主流程接入共享核心
`harbor_core`（gpmc 同步快照 `1ac9c2efc7c1b967`，22 个锁定文件）；
自建本机清理按 Yuto 2026-11-13 决定取消，改为 Google 相册官方释放空间指引。

- 新增 `HarborCore.kt`（薄适配器：`dispatch`/`createTask`/`transition`/
  `decidePrepare`/`decideCleanup`/`retryDelay`/`store_load`/`log_event`），
  状态字符串与核心一致；`BackupRepository.processPhotoWithCore` 走
  create_task → prepare → 引擎 `lookup` → decide_prepare（命中 dedup_hit
  跳过上传）→ uploading → legacy 传输 → submitting/confirming → 云端确认后
  done；失败/暂停/断网走核心 transition，重试用 retry_delay；重启后
  `recoverCoreTasks()` 把 uploading/submitting/confirming 恢复为 interrupted。
  旧直接调用路径保留为 fallback，注释标 `deprecated-by-unification`。
- 引擎新增 `lookup` 动作（`harbor_android.py`，复用 `check_digest` +
  `find_remote_media_by_hash`，只查不传不删），`engine-extension.json`
  的 sha256 已随之更新并通过 `tools/verify_engine.py`。
- 清理取消：删除 `CleanupWorker.kt`、`CleanupRepository.kt`、
  `CleanupPolicy.kt`、`CleanupPolicyUi.kt`、`CheckTimingUi.kt`、
  `CheckPerformance.kt` 及相关 ViewModel/DAO/设置/UI/测试入口；新增
  `FreeSpaceGuide.kt`（优先打开已验证 Universal Link
  `https://photos.google.com/link/freeupspace`，回退：启动 Google 相册应用
  → Play 商店详情页 → 手动文案）与 `FreeSpaceScreen` 指引页；只有云端确认
  的备份才提示去官方清理。用户自选单项本机删除（系统同意流程）保留。
  Room 旧清理表只读保留，未做不可逆删除，待 Yuto 早上定夺（文档审阅 D1）。
- 深链验证状态：该链接在 iOS 0.2.12 已验证（AASA 有据 + 用户 iPhone 实测
  直达）；Android 侧未实机验证。不要编造其他 `googlephotos://` 深链。
- 脱敏口径：文件名只记 basename，禁止完整路径、内容哈希、凭据/账号标识；
  日志仍 14 天 / 64 MiB 滚动与导出入口不变。
- 测试：删 `CleanupPolicyTest.kt`、`CheckParallelPolicyTest.kt`（其中两个
  通用 `EngineCallback` 信号量用例移入新 `EngineCallbackTest.kt`）；
  `PolicyTest.kt` 删清理过期用例；`DeviceRegressionTest.kt` 删 8 个清理
  @Test，保留 `deviceRegression`（Python 证据键）与 `manualQueueAndVault`；
  新增 `FreeSpaceGuideTest.kt`（验证链接精确值、包名 Intent、商店回退、
  无删除动作、回退顺序）。
- 本地验证：`tools/test_diagnostics.py` 4 项通过；`harbor_android.py`、
  `picharbor_engine.py`、`harbor_diagnostics.py`、`gpmc/harbor_core.py`
  语法检查通过；`tools/verify_engine.py` 与 `tools/sync_gpmc.py --check`
  均 PASS；另有一次性脚本验证引擎 `lookup` 命中/未命中、核心去重路径、
  上传路径与非法边拒绝、retry_delay、store 往返与 interrupted 恢复、
  log_event 脱敏（脚本在 /tmp，未入仓）。Kotlin 未本地编译（本机无 Java），
  需 CI/实机验证；核心集成未做端到端实测（无真实账号/设备）。
