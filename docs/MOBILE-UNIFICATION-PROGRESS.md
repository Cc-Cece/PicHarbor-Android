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
