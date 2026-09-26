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

## Alternatives considered

- 将标题与评论快照写入投影：读取更简单，但移出项目后会泄露历史业务文本，且编辑删除后难以保持一致。
- notification 直接读取其他模块表：能减少部分查询，但破坏模块边界并复制授权规则。
- 目标消失仍抛出不可重试异常：会把事件标为 DEAD，阻塞同聚合之后的事件；只有数据库故障值得重试。
- 首期引入 SSE、投递确认和持久化投递任务：实时性更好，但对便携 Electron 壳、重连和消费水位引入额外维护成本，暂选有界轮询。

## Consequences

通知内容反映当前可见事实，不是历史审计快照；被移出后保留通知和计数但隐藏目标。当前页读取只做固定数量批量调用，不做逐条业务查询。若并发用户与轮询读压显著增加，或产品要求低于 30 秒的提醒延迟，需要重新评估 SSE 与批量投递模型。迁移水位意味着部署前积压不补发，后续增加消费者版本也必须明确回溯边界。桌面提醒的最小文本、账号水位和偏好独立性由桌面提醒 Note 维护。
