# PicHarbor 计划、同步与分支交接总结

更新时间：2026-11-12（北京时间）。本文件是本次收尾快照，后续以 GitHub 实际分支和 CI 为准。

## 推荐从哪里继续

| 工作范围 | 仓库与推荐起点 | 说明 |
| --- | --- | --- |
| 共享协议、上传、认证、公共错误与证据规则 | [Cc-Cece/gpmc](https://github.com/Cc-Cece/gpmc/tree/baseline/mobile-unification-20261112) 的 `baseline/mobile-unification-20261112` | 从中央库 main 建立；唯一 gpmc 维护源不变 |
| Android 日志、业务与共享核心接入 | [PicHarbor-Android](https://github.com/Cc-Cece/PicHarbor-Android/tree/baseline/mobile-unification-20261112) 的 `baseline/mobile-unification-20261112` | 从诊断分支建立，已包含诊断体系和 gpmc |
| iOS 继续当前整合或承接最新代码 | [PicHarbor-iOS](https://github.com/Cc-Cece/PicHarbor-iOS/tree/baseline/mobile-unification-20261112) 的 `baseline/mobile-unification-20261112` | 从最新整合分支建立，仍有 3 项原生测试失败，不视为稳定发布基线 |
| 电脑端业务与共享核心接入 | [PicHarbor](https://github.com/Cc-Cece/PicHarbor/tree/baseline/mobile-unification-20261112) 的 `baseline/mobile-unification-20261112` | 从已合并 gpmc、完整 CI 通过的功能分支建立 |

以上是延续本次工作的入口，并不表示所有历史功能已合入。新任务宜从对应起点建立新分支。若只修已发布版本，从该仓库 `origin/main` 或对应发布标签建立维护分支，并单独处理向功能线回合；不能把较新功能分支直接当作发布主线。

## 统一命名的后续基线

按用户要求，四仓库均已建立并推送 `baseline/mobile-unification-20261112`，四个主工作区均已切换至该分支。它表示同一工作阶段的起点，不表示四仓库提交 SHA 相同，也没有将旧功能额外合并或改变 main。

| 仓库 | 创建来源 | 创建时提交 |
| --- | --- | --- |
| Android | `feat/android-diagnostics` | `f7845a65a4b622e2a901aac853d1c39308134c32` |
| iOS | `feat/unified-gpmc` | `90ac7e16130ecdfb4845bc86225c23a1e65698f9` |
| 电脑 | `feat/paired-backup-scope` | `a4b687688e245f60f8149c9a3e1d09471925109d` |
| gpmc | `main` | `a5d11cffbe021d02f0fc686d2953c53c36f438a3` |

本总结的更新提交仅增加在 Android 基线上，未改业务代码。后续任务从各仓库此同名基线创建任务分支；正式合并和发布仍分别执行审核与必要验证。共享 gpmc 的正式维护主线仍为中央库 main，客户端依旧通过锁定快照接入。现有 iOS PR #2 的来源和目标保持不变；如在新任务分支修复其阻塞，应将相应修复同步回该 PR，避免两条活动实现分叉。

Android 本地 `main` 比 `origin/main` 落后两次提交，且 `origin/main` 与诊断分支存在分叉（主线独有 2 次、诊断分支独有 12 次；本总结提交前）。本次没有为同步而自动合并这些分支。

## 已敲定的计划与边界

1. **移动端采用共享业务逻辑、分别使用原生 UI。** 以 iOS 功能、交互和业务行为为主要蓝本，保留 SwiftUI 和安卓原生界面。旧安卓作为辅助参考，继承平台适配、日志和构建经验。详细任务书：[MOBILE-UNIFICATION-CHARTER.md](MOBILE-UNIFICATION-CHARTER.md)。
2. **渐进迁移。** 先盘点和可运行技术验证，再打通普通照片备份完整流程，逐批迁移其余能力，最后进行数据迁移和发布切换。Kotlin Multiplatform、共享核心语言、统一仓库位置、数据库及旧数据迁移均未决定。
3. **gpmc 统一维护于本地 `../gpmc` / GitHub `Cc-Cece/gpmc`。** 三端使用同一内容锁定的源码快照；通用改进先进入中央库，再用同步工具更新客户端并执行离线校验。不能直接长期修改各端副本。平台 UI、任务数据库和系统执行接口仍由客户端维护。见 [维护入口](GPMC-MAINTENANCE.md)及[中央维护说明](https://github.com/Cc-Cece/gpmc/blob/main/docs/MAINTENANCE.md)。
4. **日志面向导出给开发者和 AI 排查，用户自读其次。** 默认开启、不可手动关闭；固定 14 天或容量上限任一满足就滚动。安卓为 64 MiB；设置只提供导出入口与脱敏说明，允许选择日期区间，默认全部现存日志。导出结构化 JSONL 和同源可读文本；允许文件名，不包含媒体本体、明文账号和密码/令牌。设计与实际实现覆盖分别见 [README](diagnostics/README.md)、[IMPLEMENTATION](diagnostics/IMPLEMENTATION.md)、[RUNBOOK](diagnostics/RUNBOOK.md)。统一移动端容量最终取值仍按任务书另行确认。
5. **安卓后续沿用 beta.5 固定签名。** 私钥和密码不入库。1.0.8 签名不同，无法直接覆盖；更改包名共存、旧数据迁移和旧版退出安排尚未决定。本次没有卸载应用、清理数据或发布新版。见 [SIGNING.md](SIGNING.md)。
6. **每完成一项记录结果。** 移动端进度入口：[MOBILE-UNIFICATION-PROGRESS.md](MOBILE-UNIFICATION-PROGRESS.md)；gpmc 详细执行记录：[INTEGRATION-LOG.md](https://github.com/Cc-Cece/gpmc/blob/main/INTEGRATION-LOG.md)。本文件补充该记录中此前“CI 运行中”的最终结果。
7. **本次合并仅限 gpmc 回到各端原功能分支。** 不把历史功能一起并入客户端 main；不自动发布应用或 PyPI。实现者自审已留 COMMENT 记录，不等于独立维护者批准。遵循用户要求，只做必要排查，未通过的 iOS PR 保留，不强行合并。

## 已完成与当前剩余项

| 范围 | 状态与证据 |
| --- | --- |
| 中央 gpmc | [PR #1](https://github.com/Cc-Cece/gpmc/pull/1) 已合 main；跨系统 Python 契约及发行包检查通过 |
| Android | [PR #3](https://github.com/Cc-Cece/PicHarbor-Android/pull/3) 已合 `feat/android-diagnostics`；无密钥 PR 验证和固定密钥签名预览通过；公开发布步骤未执行 |
| 电脑 | [PR #13](https://github.com/Cc-Cece/PicHarbor/pull/13) 已合 `feat/paired-backup-scope`；完整 CI、EXE E2E、发布包启动与密钥扫描通过 |
| iOS IPA | 最新提交 `90ac7e1` 的[无签名 IPA / 内容检查](https://github.com/Cc-Cece/PicHarbor-iOS/actions/runs/43226154463)通过 |
| iOS 原生回归 | 同提交的[完整模拟器检查](https://github.com/Cc-Cece/PicHarbor-iOS/actions/runs/43226154508)失败：135 单元测试中 1 失败、14 UI 测试中 2 失败，总计 149 项、3 失败；本轮完整执行结束，不是测试阶段超时 |

iOS 当前失败：`SubscriptionQRCodeTests.testPhotoQRCodeIsRecognizedLocally` 返回空识别结果；`HarborFlowTests.testActivityGroupingOptions` 无法操作活动菜单；`HarborFlowTests.testArchiveSettingsValidateInputAndArchiveEntryOpens` 输入框未获键盘焦点。原因尚未全部证实，不把这些问题断言为 gpmc 回归，也不宣称原生验收通过。最新工具链为 IPA Xcode 26.3、原生回归 Xcode 16.4，模拟器与 SDK 匹配；后续仅围绕阻塞项决定修复或另行分拆范围。

下一项必要收尾是处理上述失败后将 [iOS PR #2](https://github.com/Cc-Cece/PicHarbor-iOS/pull/2) 合回 `feat/paired-backup-scope`，再更新完成记录。真实 Google 账户、手机后台恢复及云端 Live Photo 验收仍未执行。整体移动端共享业务架构尚未完成，当前完成的是 gpmc 通用能力整合与各端接入代码。

现有堆叠 PR：Android #1、iOS #1、电脑 #12 均是 `feat/paired-backup-scope` → `feat/live-link`，不是指向 main。本次不改变它们的范围或合并状态。

## 本次同步结果

- 检查四个主工作区及额外的 `desktop-gui`、`ios-proxy-validation` 工作区。
- 四仓库原有本地分支的提交均已可从 origin 远程分支访问，没有发现仅本地保存、完全未上传的代码提交。
- 电脑端补建远程分支名 `feat/pcl2-ui-redesign`、`feat/sweet-album-gallery`、`feature/v3-semantic-refactor` 并设置跟踪；`feature/v4-iphone-backup` 已在 GitHub，仅补设本地跟踪。保留全部历史分支，未合并或删除。
- iOS 额外工作区发现一处未提交路径更新：`Vendor/android-engine-baseline.json` 的历史来源目录改为当前电脑端路径；已作为 `3a9498f` 提交并推送至 `proxy-validation-0215`。只更新位置，没有修改来源提交、哈希或引擎内容。
- 本总结提交在 Android `feat/android-diagnostics` 并推送。下方 Android HEAD 表记录的是总结写入前的代码基线，避免文件自引用提交哈希。
- 本次不修改业务代码，不触发手动发布，不为文档编写额外测试；路径 JSON 解析及文档差异做必要检查。

## 分支分布快照

以下列出全部本地分支及 GitHub 对应分支。`当前` 指主工作区选中分支；额外工作区另列。提交缩写用于识别，不代表版本支持承诺。

### android

| Branch | Local HEAD | GitHub HEAD | State |
| --- | --- | --- | --- |
| [`baseline/mobile-unification-20261112`](https://github.com/Cc-Cece/PicHarbor-Android/tree/baseline/mobile-unification-20261112) | `f7845a6` | `f7845a6` | synced; current |
| [`docs/gpmc-review-results`](https://github.com/Cc-Cece/PicHarbor-Android/tree/docs/gpmc-review-results) | `37173cb` | `37173cb` | synced |
| [`feat/android-diagnostics`](https://github.com/Cc-Cece/PicHarbor-Android/tree/feat/android-diagnostics) | `d10c073` | `d10c073` | synced |
| [`feat/live-link`](https://github.com/Cc-Cece/PicHarbor-Android/tree/feat/live-link) | `bce7ed7` | `bce7ed7` | synced |
| [`feat/paired-backup-scope`](https://github.com/Cc-Cece/PicHarbor-Android/tree/feat/paired-backup-scope) | `3bcabe8` | `3bcabe8` | synced |
| [`feat/unified-gpmc`](https://github.com/Cc-Cece/PicHarbor-Android/tree/feat/unified-gpmc) | `0e34f76` | `0e34f76` | synced |
| [`main`](https://github.com/Cc-Cece/PicHarbor-Android/tree/main) | `1febf56` | `5441261` | remote differs |

### ios

| Branch | Local HEAD | GitHub HEAD | State |
| --- | --- | --- | --- |
| [`baseline/mobile-unification-20261112`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/baseline/mobile-unification-20261112) | `90ac7e1` | `90ac7e1` | synced; current |
| [`feat/live-link`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/feat/live-link) | `71f2021` | `71f2021` | synced |
| [`feat/paired-backup-scope`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/feat/paired-backup-scope) | `0d95a07` | `0d95a07` | synced |
| [`feat/unified-gpmc`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/feat/unified-gpmc) | `90ac7e1` | `90ac7e1` | synced |
| [`main`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/main) | `f3cf627` | `f3cf627` | synced |
| [`proxy-validation-0215`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/proxy-validation-0215) | `3a9498f` | `3a9498f` | synced |
| [`verify-google-photos-handoff-0211-2bd2d06`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/verify-google-photos-handoff-0211-2bd2d06) | - | `2bd2d06` | remote only |
| [`verify-live-share-0214`](https://github.com/Cc-Cece/PicHarbor-iOS/tree/verify-live-share-0214) | - | `d53b0e7` | remote only |

### desktop

| Branch | Local HEAD | GitHub HEAD | State |
| --- | --- | --- | --- |
| [`baseline/mobile-unification-20261112`](https://github.com/Cc-Cece/PicHarbor/tree/baseline/mobile-unification-20261112) | `a4b6876` | `a4b6876` | synced; current |
| [`docs/publish-readme`](https://github.com/Cc-Cece/PicHarbor/tree/docs/publish-readme) | `d5af668` | `d5af668` | synced |
| [`feat/android-backup-to-pc`](https://github.com/Cc-Cece/PicHarbor/tree/feat/android-backup-to-pc) | `9a00202` | `9a00202` | synced |
| [`feat/backup-history-and-db-maintenance`](https://github.com/Cc-Cece/PicHarbor/tree/feat/backup-history-and-db-maintenance) | `cd77230` | `cd77230` | synced |
| [`feat/google-login-webview-proxy`](https://github.com/Cc-Cece/PicHarbor/tree/feat/google-login-webview-proxy) | `8009d2b` | `8009d2b` | synced |
| [`feat/live-link`](https://github.com/Cc-Cece/PicHarbor/tree/feat/live-link) | `fb15e70` | `fb15e70` | synced |
| [`feat/paired-backup-scope`](https://github.com/Cc-Cece/PicHarbor/tree/feat/paired-backup-scope) | `a4b6876` | `a4b6876` | synced |
| [`feat/pcl2-ui-redesign`](https://github.com/Cc-Cece/PicHarbor/tree/feat/pcl2-ui-redesign) | `fb5cf85` | `fb5cf85` | synced |
| [`feat/sweet-album-gallery`](https://github.com/Cc-Cece/PicHarbor/tree/feat/sweet-album-gallery) | `889d0fc` | `889d0fc` | synced |
| [`feat/unified-gpmc`](https://github.com/Cc-Cece/PicHarbor/tree/feat/unified-gpmc) | `7077bfc` | `7077bfc` | synced |
| [`feature/gui-search-and-apple-devices`](https://github.com/Cc-Cece/PicHarbor/tree/feature/gui-search-and-apple-devices) | `5488d40` | `5488d40` | synced |
| [`feature/iphone-restore`](https://github.com/Cc-Cece/PicHarbor/tree/feature/iphone-restore) | `bcc2187` | `bcc2187` | synced |
| [`feature/v3-semantic-refactor`](https://github.com/Cc-Cece/PicHarbor/tree/feature/v3-semantic-refactor) | `85eae7c` | `85eae7c` | synced |
| [`feature/v4-iphone-backup`](https://github.com/Cc-Cece/PicHarbor/tree/feature/v4-iphone-backup) | `3207e10` | `3207e10` | synced |
| [`feature/website-and-pages-action`](https://github.com/Cc-Cece/PicHarbor/tree/feature/website-and-pages-action) | `3dc1e40` | `3dc1e40` | synced |
| [`fix/pages-workflow-yaml-syntax`](https://github.com/Cc-Cece/PicHarbor/tree/fix/pages-workflow-yaml-syntax) | `9eee85d` | `9eee85d` | synced |
| [`main`](https://github.com/Cc-Cece/PicHarbor/tree/main) | `4d99ee1` | `4d99ee1` | synced |
| [`refactor/rename-to-picharbor`](https://github.com/Cc-Cece/PicHarbor/tree/refactor/rename-to-picharbor) | `8f77650` | `8f77650` | synced |
| [`docs/protection-wording`](https://github.com/Cc-Cece/PicHarbor/tree/docs/protection-wording) | - | `04e2708` | remote only |

### gpmc

| Branch | Local HEAD | GitHub HEAD | State |
| --- | --- | --- | --- |
| [`baseline/mobile-unification-20261112`](https://github.com/Cc-Cece/gpmc/tree/baseline/mobile-unification-20261112) | `a5d11cf` | `a5d11cf` | synced; current |
| [`docs/gpmc-review-results`](https://github.com/Cc-Cece/gpmc/tree/docs/gpmc-review-results) | `31c1ad4` | `31c1ad4` | synced |
| [`feat/unified-gpmc`](https://github.com/Cc-Cece/gpmc/tree/feat/unified-gpmc) | `08d9ae6` | `08d9ae6` | synced |
| [`main`](https://github.com/Cc-Cece/gpmc/tree/main) | `a5d11cf` | `a5d11cf` | synced |
| [`release-please--branches--main--components--gpmc`](https://github.com/Cc-Cece/gpmc/tree/release-please--branches--main--components--gpmc) | - | `a9eb736` | remote only |

### Extra worktrees

- `C:/Users/Kanbara/Code/03_worktrees/01_PicHarbor/desktop-gui`: `feature/gui-search-and-apple-devices`.
- `C:/Users/Kanbara/Code/03_worktrees/01_PicHarbor/ios-proxy-validation`: `proxy-validation-0215`.
