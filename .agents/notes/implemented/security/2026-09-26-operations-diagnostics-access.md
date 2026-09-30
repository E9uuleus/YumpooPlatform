# Agent Note: 运维诊断数据的访问与脱敏边界

Status: implemented

## Problem

2026-09-26 的运维中心方案选择让负责服务器的应用管理员在界面中排障。日志关联用户、接口和受控异常堆栈；这些数据的读取范围、留痕和暴露方式需要独立于运行时架构记录，便于后续权限复审。

## Decision

- 每项运维 API 重新校验当前有效 `APP_MANAGER`，公司管理员不自动获得权限。入口和路由同步限制，读写及错误响应均禁止缓存，角色撤销导致正在展示的数据清空。
- System Log 使用内部 `userId` UUID 关联请求，允许 `APP_MANAGER` 读取统一映射处理后的异常。堆栈限制长度和帧数，文件及浏览器都不接受原始 Throwable 的无界输出。会话详情在 SQL 中按公司过滤，采样器的跨公司会话端口只返回汇总数量。
- 凭据、请求正文、SQL 明细、电子邮件、手机号、IP 和文件路径需脱敏；URL 保留端点，移除 userinfo 和查询数据，保留之后的故障原因。pgJDBC 关闭服务器明细输出。三种日志输出共用映射规则，规则预编译，缓冲映射在写锁外执行。
- 管理读取保留 INFO 访问日志及安全路由；仅 `logs/tail` 与 `alerts/summary` 两个正常轮询请求使用 DEBUG。失败、慢请求不因轮询而降级。规则变更与确认操作使用 CSRF 和安全审计，规则修改另外要求条件版本；不为每次日志读取写数据库审计以免形成反馈循环。
- 文件检索只允许配置的活动文件及规定归档，拒绝任意路径及符号链接，限制扫描预算和并发。无 `fileKey` 的 Windows 文件以创建时间、大小不回退及前缀内容验证快照；滚动或替换使游标结果显式 partial。

行为依据：[访问日志](../../../../backend/src/main/java/com/yumpoo/platform/foundation/api/web/HttpAccessLog.java)、[日志脱敏](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/logging/LogSanitizer.java)、[文件检索](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/logging/LogQueryAdapter.java)、[会话查询](../../../../backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/session/JdbcActiveSessionQueryRepository.java)、[HTTP 集成验证](../../../../backend/src/test/java/com/yumpoo/platform/operations/api/OperationsHttpIT.java)。运行时设计由[架构 Note](../architecture/2026-09-26-operations-center.md)维护。

## Alternatives considered

- HMAC actorRef 增加密钥轮换和查询映射成本；当前应用管理员同时管理身份与主机，内部 UUID 已能满足排障和最小身份暴露的需求，不使用企微 userId 或姓名作为请求日志字段。
- 只允许服务器本地读取堆栈会削弱运维中心的排障价值；采用管理员访问、统一脱敏和有界堆栈。此选择不允许非管理员或公网匿名访问。
- 完全隐藏 URL 和路径之后的消息会同时丢失超时、磁盘满等根因；限制脱敏匹配范围，保留故障描述。
- 全部运维 GET 降为 DEBUG 会失去敏感管理读取的追踪证据；仅为两个高频轮询端点降噪。

## Consequences

- 应用内日志不是不可篡改审计仓；安全写操作的审计仍在数据库中。生产日志目录继续受部署 ACL 和轮转保留限制。
- 正则脱敏无法证明任意自由文本均无敏感数据，调用方仍应只记录稳定事件与允许的结构化字段，不主动记录请求正文或凭据。
- 数据库不可达时不绕过认证来暴露诊断数据。进程停止和数据库完全故障期间的可用性监控留给后续外部探活。
