# 原样引擎兼容性记录

## 已验证

- gpmc 与 blackboxprotobuf 共 31 个源码文件逐字节匹配冻结基线。
- CPython 3.13 上可导入完整引擎，配套 Protobuf 编解码往返通过。
- Android arm64 APK 可构建，包含 Chaquopy Python 3.13 运行时、完整引擎和依赖。

## 尚需真机验证

- Android `Application.onCreate` 首次启动 Python 并导入 gpmc，确保其导入阶段 `signal.signal` 位于 Python 主线程。
- WebView EmbeddedSetup 登录可能被 Google 拒绝；当前使用真实 WebView UA，没有修改 UA 或读取其他应用数据。
- 设备验证、令牌绑定和加密令牌取决于服务端。原样 gpmc 遇到加密令牌会失败，适配层明确分类为阻塞。
- gpmc 内部访问令牌续期必须等待实际到期后验证。

## 修改约束

没有修改 gpmc 或 blackboxprotobuf 源文件。若真机出现 `SIGNAL_MAIN_THREAD_REQUIRED`，先核查 Python 首次启动及导入线程；不得 monkey patch `signal`。若仍必须更改引擎，记录设备、错误类别、最小修改建议及影响，先保留本次原样验证结论。

应用不提供自定义 HTTP 代理设置，沿用无代理参数的原有 gpmc 网络路径；可使用手机已配置的网络。没有为 Android 调试关闭 TLS 证书检查。
