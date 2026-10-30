# Agent Note: 运维诊断数据的访问与脱敏边界

Status: implemented

## Problem

2026-09-26 的运维中心方案选择让负责服务器的应用管理员在界面中排障。日志关联用户、接口和受控异常堆栈；这些数据的读取范围、留痕和暴露方式需要独立于运行时架构记录，便于后续权限复审。

## Decision

- 每项运维 API 重新校验当前有效 `APP_MANAGER`，公司管理员不自动获得权限。入口和路由同步限制，读写及错误响应均禁止缓存，角色撤销导致正在展示的数据清空。
- System Log 使用内部 `userId` UUID 关联请求，允许 `APP_MANAGER` 读取统一映射处理后的异常。堆栈限制长度和帧数；每段 throwable 的前 8 帧不按包名过滤，以保留容器、过滤器内的真实抛出点，此后照旧滤掉 `org.apache.`、Spring Web 过滤器、Spring Security Web 与反射帧，单段上限 40 帧不变。文件及浏览器都不接受原始 Throwable 的无界输出。会话详情在 SQL 中按公司过滤，采样器的跨公司会话端口只返回汇总数量。
- 凭据、请求正文、SQL 明细、电子邮件、手机号、IP 和文件路径需脱敏；URL 保留端点，移除 userinfo 和查询数据，保留之后的故障原因。pgJDBC 关闭服务器明细输出。三种日志输出共用映射规则，规则预编译，缓冲映射在写锁外执行。
- 管理读取保留 INFO 访问日志及安全路由；仅 `logs/tail` 与 `alerts/summary` 两个正常轮询请求使用 DEBUG。失败、慢请求不因轮询而降级。规则变更与确认操作使用 CSRF 和安全审计，规则修改另外要求条件版本；不为每次日志读取写数据库审计以免形成反馈循环。
- 应用层拒绝在同一条访问日志中附带稳定 `errorCode` 和 `reason`，403 使用 WARN，保留 `requestId`、方法及模板路由供运维追踪。界面摘要展示拒绝码与原因；不记录请求正文、人工操作理由或任意异常消息。最近认证过期可通过 `RECENT_AUTHENTICATION_REQUIRED` 定位，数据库安全审计仍独立保存。被拒绝的请求还记录 fieldErrors（字段路径:错误码，最多 10 项，不含字段值与提示文本）和接口声明的处理阶段 stage；Bean Validation 产生的 422 同样记录 errorCode，见[错误分类与诊断](../architecture/2026-10-30-error-classification-and-diagnostics.md)。
- 文件检索只允许配置的活动文件及规定归档，拒绝任意路径及符号链接，限制扫描预算和并发。无 `fileKey` 的 Windows 文件以创建时间、大小不回退及前缀内容验证快照；滚动或替换使游标结果显式 partial。

- `RequestIdFilter` 同时处理异步与错误派发：沿用首次派发的 requestId，不重写响应头，并从请求属性恢复 `userId`、`clientType` 到 MDC；访问日志与业务耗时只在首次派发记录；响应已提交的失败照旧抛出，不把截断的下载记成成功。

行为依据：[RequestIdFilter](../../../../backend/src/main/java/com/yumpoo/platform/foundation/api/web/RequestIdFilter.java)、[访问日志](../../../../backend/src/main/java/com/yumpoo/platform/foundation/api/web/HttpAccessLog.java)、[日志脱敏](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/logging/LogSanitizer.java)、[文件检索](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/logging/LogQueryAdapter.java)、[会话查询](../../../../backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/session/JdbcActiveSessionQueryRepository.java)、[HTTP 集成验证](../../../../backend/src/test/java/com/yumpoo/platform/operations/api/OperationsHttpIT.java)。运行时设计由[架构 Note](../architecture/2026-09-26-operations-center.md)维护。

## Alternatives considered

- HMAC actorRef 增加密钥轮换和查询映射成本；当前应用管理员同时管理身份与主机，内部 UUID 已能满足排障和最小身份暴露的需求，不使用企微 userId 或姓名作为请求日志字段。
- 只允许服务器本地读取堆栈会削弱运维中心的排障价值；采用管理员访问、统一脱敏和有界堆栈。此选择不允许非管理员或公网匿名访问。
- 完全隐藏 URL 和路径之后的消息会同时丢失超时、磁盘满等根因；限制脱敏匹配范围，保留故障描述。
- 全部运维 GET 降为 DEBUG 会失去敏感管理读取的追踪证据；仅为两个高频轮询端点降噪。

## Consequences

- 应用内日志不是不可篡改审计仓；安全写操作的审计仍在数据库中。生产日志目录继续受部署 ACL 和轮转保留限制。
- 正则脱敏无法证明任意自由文本均无敏感数据，调用方仍应只记录稳定事件与允许的结构化字段，不主动记录请求正文或凭据。
- 数据库不可达时不绕过认证来暴露诊断数据。进程停止和数据库完全故障期间的可用性由[异机监控](../architecture/2026-10-23-offsite-monitoring-notifications.md)负责。内部 Prometheus 出口仅返回固定低基数指标与已提交告警缓存，不暴露日志、身份或任意诊断，也不依赖业务数据库认证。
