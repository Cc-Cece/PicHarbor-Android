# 正式版引擎改动记录

来源提交、复制时间与 31 个逐文件哈希仍在 engine-baseline.json。首次复制的 gpmc / blackboxprotobuf 文件保持原样，未覆盖基线。原桌面桥和验证版入口留作 reference，正式 APK 不含 android_probe。

新增 gpmc/mobile.py，业务逻辑集中在完整 gpmc 包内：

- MobileApi 继承原 Api，复用认证、续期、protobuf 与上传参数；增加认证互斥和有限重试传输约束，无 monkey patch。
- MobileService 提供 connect / process / synchronize / reconcile / albums / verify_cleanup / fingerprint / logout。
- 文件描述符或单文件暂存、SHA-1/SHA-256、取消与进度、内容查重、三种质量、相册索引及归类重试。
- 账号云端 SQLite，初始/增量分页，缺失字段保留未知，云端删除使旧内容证据失效。
- 清理仅提供证据，HEAD/限量 Range 检查，不实现本地或云端删除。
- 对外仅限定错误码，原始认证/HTTP 异常不进入界面与诊断报告。

新增 picharbor_engine.py 是初始化与 JSON 薄转发，Android 不实现 Google 协议。扩展哈希单独记录于 engine-extension.json；verify_engine.py 核对原始与扩展，维护时只更新扩展清单，不能重置原始基线。

离线测试不替代真实账号和设备的登录、实际到期续期、原件及配额验收。

## 1.0.2

扩展接口加入调用账号校验，防止旧任务在账号切换后使用新账号请求。清理前强制完整云端同步，关联本次快照标识和完成时间；失败时立即废止之前的快照。经 Android 14 真机正常／回收站／恢复对照，区分回收站子消息中明确的空可选时间戳与缺失、畸形信息；只有新鲜完整快照才可采用空标记语义。仍需原始画质、完成状态、大小、内容哈希和原件可访问证据。新增字段缺失、云端仍在处理和原件网络失败的分项原因。31 个原始文件及上传质量、免配额、登录和续期请求未改动。

## 1.0.4

mobile 扩展增加线程独立的阶段计时及计数，成功和限定错误响应均可附带 `_metrics`。文件读取通过 Android 提供的公平信号量控制，哈希完成即关闭句柄并释放名额，不在网络检查期间占用。`verify_cleanup` 的 quick 模式只读取哈希及调用内容查询，始终返回 `eligible=false`；不读取完整快照，不访问原件，不生成删除凭证。完整模式保留既有规则。原件访问遇到 429 或服务端 5xx 返回限定网络错误供 Android 有限重试。原始 31 个文件不变，扩展哈希更新至 1.0.4。
