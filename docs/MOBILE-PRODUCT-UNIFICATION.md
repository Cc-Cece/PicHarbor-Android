# 手机端产品统一

共同规范与完整验收状态由中央库维护：

- [移动端共同产品规范](https://github.com/Cc-Cece/gpmc/blob/feat/mobile-product-unification/docs/MOBILE-PRODUCT-CONTRACT.md)
- [本轮实施与交付记录](https://github.com/Cc-Cece/gpmc/blob/feat/mobile-product-unification/docs/MOBILE-IMPLEMENTATION-2026-11-17.md)

产品蓝本固定为 iOS `90ac7e1`。Android 新包名 `com.cccece.picharbor.mobile`，新版本线 `2.0.0-alpha.1`。
与旧版独立安装，不导入旧数据。签名与恢复见 [SIGNING.md](SIGNING.md)。

四栏顺序统一为照片、备份、活动、设置；原生 Compose 页面继续独立维护。
共享核心来自中央 gpmc，不能直接修改 `app/src/main/python/gpmc` 副本。

本轮计划的开发实现、release 构建和设备安装已完成。最终功能源码为 `c6b8904`，共享核心内容哈希为 `0a4472b9ffd4537b`。用户要求自行测试，开发交付与最终实机验收分别记录；目前没有声明真实云端与跨端实机验收通过。
