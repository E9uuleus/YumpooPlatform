# Agent Note: 站内收件箱投影、按读者渲染与可见性轮询

Status: implemented

## Problem

用户需要在同一入口读取评论、提及、指派和项目成员变化。通知属于跨模块派生视图，不能为了显示摘要让 notification 直接读取 catalog/workitem 内部数据，也不能把当时有权接收误当作永久有权查看。Outbox 的失败和聚合有序处理意味着一个已经删除的目标若不断重试，会阻塞后续正常业务事件。

## Decision

notification 仅依赖 foundation 与 identityaccess，通过 `NotificationContextPort` 请求业务上下文；API 桥接器把公开 DTO 转换为 application 内部模型，内部层不反向依赖本模块 API；administration 的 `NotificationContextAdapter` 组合 catalog 和 workitem 的公开端口。消费时按账号 ACTIVE/ENABLED 与当前项目 ACTIVE 成员资格取交集；被移出成员本人仅校验账号。作者不接收自己的通知，单事件同一接收人的原因优先级为 MENTION、REPLY、COMMENT。负责人转交引起的自动 member_added 不单独投递。评论接收人的业务发起人取 `work_item.reporter_user_id`，与公开工作项契约中的 `reporterUserId` 一致，不使用审计字段 `created_by_user_id`；常规创建时两者相同，但导入或代理创建后的业务归属不能靠审计创建人推断。仅在被回复父评论仍未删除时加入其作者；父评论已为 DELETED 时不产生该 REPLY 接收人。

V57 只建立 `notification_projection_state`、`notification_event` 和 `user_notification`。数据库唯一键及 `ON CONFLICT DO NOTHING` 支持重放；不为项目、工作项和接收人建立额外外键，以免通知投影反向约束源模块的数据清理。只保存目标与人员 ID，不持久化正文和标题。读取使用当前主体的批量可见性端口，评论摘要实时压缩空白后取 120 字；目标不可访问时返回 `accessible=false` 且全部目标引用置空。页内查询按集合批量执行，SQL 数量不随通知条数线性增长。非空混合目标页的渲染最多 9 次业务 SQL，加列表与数据库时钟共最多 11 次（不含会话认证）；高于最初约 5 次的估计，是因为工作项引用与评论端口各自重新校验当前主体的项目范围，避免把调用方传入的项目 ID 当作授权凭据。空集合会短路，单独未读计数只用 1 次 SQL。

`notification-inbox-v1` 仅消费迁移 `accepted_from` 之后的事件。载荷非法、目标消失等非数据库运行期错误记录事件标识并结束消费，避免将不可恢复目标变成 RETRY/DEAD；`DataAccessException` 保留原异常以触发数据库故障重试。订阅明确包含 work_item_created/update_published/update_edited 的 v2，以及 assigned 和项目成员/负责人事件的 v1；这是对[冻结事件契约](../data/2026-08-31-work-item-event-contract-freeze.md)通知触发清单的增量，未将所有历史版本重新投递。业务引用沿用 [M3 公开端口约束](2026-08-31-m2-exit-and-m3-public-ports.md)。

HTTP 提供本人分页、分组计数、read/unread/archive 和带 `upTo` 水位的 read-all。游标绑定公司、用户、状态与分组，排序使用投影创建时间和 ID；本人状态设置天然幂等，仅要求会话与 XSRF，不增加幂等键或资源版本门槛。已归档通知执行 read 时只补 `read_at`，保留 ARCHIVED 状态及原 `archived_at`，重复 read 不改变已有已读时间；阅读归档内容不会将其移回普通列表。跨用户 ID 返回 404，响应 `Cache-Control: no-store`。水位来自数据库时钟，并裁剪未来传入时间，避免请求完成前后到达的新通知被无意标为已读。

Web 采用页面可见时每 30 秒轮询计数，并在 focus、online、visibilitychange 刷新；Electron 主窗口保持轮询。首次加载、未读水位或总数变化、跨标签页失效消息、状态修改都会刷新最新未读列表；打开收件箱另行读取当前筛选列表。这先解决站内提醒，Delivery/Attempt、SSE/ACK、管理重试、投递偏好与保留期仍不在本次模型内。

UP5c 为 `workitem.connection_created` v1 建立独立消费分支，先检查 `origin=CREATED`，再仅通过 `targetProjectId`、`targetWorkItemId` 获取目标项目当前 OWNER。接收人须为启用账号和目标项目有效成员，且不是操作者；目标工作项必须属于该目标项目。使用新增原因 `CONNECTION_CREATED`、既有 `WORK_ITEM` 目标与 ID 字段，不保存任何来源项目/工作项/连接/列标识或列名、标题、正文。列表、未读计数及按组 read-all 均将该原因归入 `PROJECT`。读取继续按当前主体批量鉴权，失权时清空全部目标引用；点击后的工作项读取仍独立鉴权，不把已收到通知作为权限凭据。

V63 追加扩展原因约束和投影代码约束，以数据库 `clock_timestamp()` 新建 `CONNECTION_CREATED_V1` 水位，保留 V57 的 `INBOX_V1` 水位与已有通知不变。连接事件只接受新水位之后发生的事件，部署前积压不补发；其他通知仍使用原水位。幂等重放沿用既有事件与接收人唯一键，既有事件载荷、冻结事件与通知 HTTP 属性均未扩展。

OpenAPI 的 `NotificationReason` 响应枚举新增值由用户明确批准；精确例外 `2026-10-03-connection-created-notification` 登记于 `tools/openapi/breaking-change-exceptions.json`。相对 PR-4 dev 基线，仅移除该新增响应枚举值的临时规范通过完整 openapi-diff，确认 UP5a 的可选查询参数兼容。例外仅绑定这两个完整规范的 SHA-256，不修改历史例外。新旧桌面壳通过[桌面提醒](../product/2026-09-25-desktop-inbox-alerts.md)的原因能力声明隔离，连接事务与事件仍由[连接架构](2026-09-30-work-item-connections.md)拥有。

## Alternatives considered

- 将标题与评论快照写入投影：读取更简单，但移出项目后会泄露历史业务文本，且编辑删除后难以保持一致。
- notification 直接读取其他模块表：能减少部分查询，但破坏模块边界并复制授权规则。
- 目标消失仍抛出不可重试异常：会把事件标为 DEAD，阻塞同聚合之后的事件；只有数据库故障值得重试。
- 首期引入 SSE、投递确认和持久化投递任务：实时性更好，但对便携 Electron 壳、重连和消费水位引入额外维护成本，暂选有界轮询。
- 复用 ASSIGNED 或成员变化原因：连接创建没有发生指派或成员变更，会使用户文案和分组含义失真，因此追加专用原因。
- 重置原收件箱部署水位：会丢弃其他类型尚未消费的合法事件，因此仅为连接通知新增独立水位。
- 保存来源快照或点击时直接信任通知目标：可能暴露来源信息或已撤销权限的内容，因此仅保存目标引用并沿用实时鉴权。

## Consequences

`NotificationInboxProjectionTest` 验证 CREATED 限定、目标引用白名单、非成员/停用账号/本人/目标不匹配排除及独立部署水位；`ConnectionNotificationIT` 验证真实连接事件、仅目标负责人接收、存储隐私、去重、PROJECT 分组和成员撤销后的隐藏与拒绝打开；`ConnectionNotificationMigrationIT` 从 V62 升级，验证历史通知和水位保留、新原因约束以及迁移重跑不改变水位。`NotificationHttpIT` 验证新原因序列化、PROJECT 列表/计数/read-all、跨用户隔离及目标删除后的隐藏和工作项 404。

通知内容反映当前可见事实，不是历史审计快照；被移出后保留通知和计数但隐藏目标。当前页读取只做固定数量批量调用，不做逐条业务查询。若并发用户与轮询读压显著增加，或产品要求低于 30 秒的提醒延迟，需要重新评估 SSE 与批量投递模型。迁移水位意味着部署前积压不补发，后续增加消费者版本也必须明确回溯边界。桌面提醒的最小文本、账号水位和偏好独立性由桌面提醒 Note 维护。
