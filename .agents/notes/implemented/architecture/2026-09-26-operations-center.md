# Agent Note: 单实例运维中心与受控日志诊断

Status: implemented

## Problem

原有 stdout JSON 难以扫读，缺少应用滚动文件、HTTP 耗时和异常根因；故障前的运行趋势在重启后丢失。公司管理员与负责服务器的应用管理员权限不同，混用公司管理入口会模糊诊断数据的可见边界。方案于 2026-09-26 提出，本次采用推荐的 D1–D7，交付 P0–P2。

## Decision

- `operations` 是独立模块。前端使用 `/admin/operations/{overview,host,sessions,logs,alerts}`，保留 `/operations` 别名；REST 位于 `/api/v1/admin/operations/**`。入口、路由和每项 API 都限定当前有效 `APP_MANAGER`，包括错误响应在内使用 `Cache-Control: no-store`；写操作复用 CSRF、`If-Match` 和安全审计。
- `foundation.application` 提供日志、部署诊断、Outbox 查询端口，`identityaccess.api` 提供有效会话端口。运维模块不依赖其他模块的 infrastructure；模块白名单及四层 marker 同步纳入架构检查。
- 控制台输出人读文本，文件输出 `yumpoo-log/1` JSON Lines；formatter 和内存缓冲共用脱敏映射。具体身份关联、受控堆栈、脱敏及读取留痕边界由[安全 Note](../security/2026-09-26-operations-diagnostics-access.md)维护；异常指纹不依赖异常消息。
- 日志文件按 UTC 日期和 50 MB 滚动压缩，保留上限为 90 天及 10 GB。生产 `logging.file.name` 必须为部署日志根内的绝对文件路径。实时缓冲分别限制为最近 10,000 条/16 MiB 与问题 2,000 条/8 MiB；过长记录计入 droppedCount。客户端最多显示 500 条，bootId 和十进制字符串 seq 表达重启、水位及缺口。
- 文件查询只访问配置文件及规定命名的归档，使用 NIO、文件身份和大小快照；无 fileKey 时校验创建时间、大小不回退及前缀内容，翻页和扫描期间的文件替换显式返回 partial，不接受调用者提供路径。单次扫描并发 1、3 秒/256 MiB，范围最多 24 小时；游标是绑定筛选的进程内句柄，最多 64 个、5 分钟过期。文件变化、损坏、预算耗尽以 partial 明示。直方图在一次扫描中汇总，避免逐页反复解压。
- 硬件信息来自 JDK/JMX、Micrometer 和 NIO，不新增 OSHI/JNA 或外部监控栈。15 秒本地采样器与现有业务调度隔离；单个 I/O worker 无排队，前次探针未完成时继续本机采样。I/O 结果超过 45 秒标记 UNKNOWN/null，磁盘每 60 秒采集。独立采集器失败不抹掉其他指标。连接池忙直接标记 DEGRADED；采样线程持续保留本机告警观测，运行中的数据库探针以已等待时长作为延迟下界。最多保留 240 份待评估观测，恢复后按采样时间顺序每轮评估至多 40 份，丢弃数通过指标和页面明示。持续窗口依据采样连续性，与 I/O 完成频率解耦。
- 内存保存 240 个样本；PostgreSQL 分钟桶按 bootId 幂等写入，保留 14 天，7/14 天查询进一步聚合。写入失败最多保留 60 个待写分钟桶，每轮补写至多 5 桶；超限明确计数并保留曲线缺口，重启不把缺失值补零。HTTP P95 使用无路由标签的 Micrometer 全局业务计时器，避免低频慢路由单独主导告警；HTTP 指标排除运维自身和 Actuator。
- 12 条内置规则采用持续超限、60 秒恢复和独立确认状态。升级清除确认以重新提示，停用规则立即结束其活跃事件。规则版本与审计同事务，活跃缓存及评估窗口仅在提交成功后发布，回滚后可以重试同一份观测。历史保留 180 天，未恢复告警不因年龄被清理。P2 出口是页面与模块栏徽标；P3 外部通知不在本次范围。

实现与行为依据：[运维模块](../../../../backend/src/main/java/com/yumpoo/platform/operations/)、[日志实现](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/logging/)、[API 契约](../../../../contracts/openapi/yumpoo-v1.yaml)、[部署手册](../../../../deployment/windows/RUNBOOK.md)、[HTTP 集成测试](../../../../backend/src/test/java/com/yumpoo/platform/operations/api/OperationsHttpIT.java)、[采样隔离测试](../../../../backend/src/test/java/com/yumpoo/platform/operations/infrastructure/OpsSamplerTest.java)。

## Alternatives considered

- 公司管理子菜单：职责和角色不一致，采用独立运维入口。
- 纯内存指标：事故伴随重启时丢失证据，采用 PostgreSQL 分钟历史；外部 Prometheus/ELK 对当前单实例部署增加不必要依赖。
- System Log HMAC actorRef 与仅服务器可见堆栈的取舍见[安全 Note](../security/2026-09-26-operations-diagnostics-access.md#alternatives-considered)。
- 与 Outbox 共用 `@Scheduled` 或在采样线程直接执行数据库/文件查询：阻塞会影响业务或抹掉本机趋势，采用独立采样与无排队 I/O worker。SQL query timeout 为 3 秒，连接获取仍受 Hikari 自身超时约束；队列隔离而非线程强杀保护业务。
- 直接向浏览器暴露文件路径或允许无限扫描：不采用。预算耗尽或归档变化时宁可明确 partial，让管理员缩小时间窗口重查，也不承诺不可实现的完整快照。

## Consequences

- 迁移追加 V58、V59；已有迁移保持不变。应用角色沿用部署脚本的 default privileges。升级保留这些运维表，回滚旧应用无需反向删除表。
- 数据库完全不可达时，身份校验和持久化告警同样不可用；不绕过当前角色校验。采样器继续收集本机数据，恢复后补记连续探针失败形成的数据库告警。进程彻底宕机须由未来 P3 外部监控发现。
- 在线/空闲依据会话最近活动的 2/30 分钟窗口，属于活跃请求近似值，不是精确用户在场探针；Web/Electron 的正常轮询会更新活跃时间。
- 分钟落库只处理已结束的桶，进程退出前未完成分钟可能缺失；曲线明确保留缺口。进程重启使日志游标失效，轮转时文件身份变化会返回 partial；不提供原始文件下载或任意路径读取。
- 验证包含后端单元/架构测试、真实 PostgreSQL HTTP 集成、前端交互回归、OpenAPI 样例与生成客户端校验，以及本机五页 UI 检查。当地 Docker Desktop 无法启动，HTTP 集成使用隔离 PostgreSQL 17.10；该替代验证不代表全量 Docker/Windows 交付链或生产环境验收已完成。
