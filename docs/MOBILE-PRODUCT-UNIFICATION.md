# 手机端产品统一

共同规范与完整验收状态由中央库维护：

- [移动端共同产品规范](https://github.com/Cc-Cece/gpmc/blob/feat/mobile-product-unification/docs/MOBILE-PRODUCT-CONTRACT.md)
- [本轮实施记录与待补齐项](https://github.com/Cc-Cece/gpmc/blob/feat/mobile-product-unification/docs/MOBILE-IMPLEMENTATION-2026-11-17.md)

产品蓝本固定为 iOS `90ac7e1`。Android 新包名 `com.cccece.picharbor.mobile`，新版本线 `2.0.0-alpha.1`。
与旧版独立安装，不导入旧数据。签名与恢复见 [SIGNING.md](SIGNING.md)。

四栏顺序统一为照片、备份、活动、设置；原生 Compose 页面继续独立维护。
共享核心来自中央 gpmc，不能直接修改 `app/src/main/python/gpmc` 副本。

当前是实施中的开发版本，功能对齐和实机验收不能仅凭新页面、能编译或生成 APK 判定完成。
