# Agent Note: 站内收件箱投影、按读者渲染与可见性轮询

Status: implemented

## Problem

用户需要在同一入口读取评论、提及、指派和项目成员变化。通知属于跨模块派生视图，不能为了显示摘要让 notification 直接读取 catalog/workitem 内部数据，也不能把当时有权接收误当作永久有权查看。Outbox 的失败和聚合有序处理意味着一个已经删除的目标若不断重试，会阻塞后续正常业务事件。

## Decision

按[项目归档访问与统计范围](../product/2026-10-09-project-archive-and-deletion.md)，归档项目对普通成员不可见。已有通知与未读计数保留，实时批量可见性端口令其目标 `accessible=false` 并清空引用与摘要；负责人和管理员仍可在授权范围只读打开历史目标。

notification 仅依赖 foundation 与 identityaccess，通过 `NotificationContextPort` 请求业务上下文；API 桥接器把公开 DTO 转换为 application 内部模型，内部层不反向依赖本模块 API；administration 的 `NotificationContextAdapter` 组合 catalog 和 workitem 的公开端口。消费时按账号 ACTIVE/ENABLED 与当前项目 ACTIVE 成员资格取交集；被移出成员本人仅校验账号。作者不接收自己的通知，单事件同一接收人的原因优先级为 MENTION、REPLY、COMMENT。负责人转交引起的自动 member_added 不单独投递。评论接收人的业务发起人取 `work_item.reporter_user_id`，与公开工作项契约中的 `reporterUserId` 一致，不使用审计字段 `created_by_user_id`；常规创建时两者相同，但导入或代理创建后的业务归属不能靠审计创建人推断。仅在被回复父评论仍未删除时加入其作者；父评论已为 DELETED 时不产生该 REPLY 接收人。

V57 只建立 `notification_projection_state`、`notification_event` 和 `user_notification`。数据库唯一键及 `ON CONFLICT DO NOTHING` 支持重放；不为项目、工作项和接收人建立额外外键，以免通知投影反向约束源模块的数据清理。保存目标与人员 ID，以及 V69 引入的删除期限时间快照，不持久化正文和标题。读取使用当前主体的批量可见性端口，评论摘要实时压缩空白后取 120 字；目标不可访问时返回 `accessible=false`，全部目标引用及删除期限置空。页内查询按集合批量执行，SQL 数量不随通知条数线性增长。非空混合目标页的渲染最多 9 次业务 SQL，加列表与数据库时钟共最多 11 次（不含会话认证）；高于最初约 5 次的估计，是因为工作项引用与评论端口各自重新校验当前主体的项目范围，避免把调用方传入的项目 ID 当作授权凭据。空集合会短路，单独未读计数只用 1 次 SQL。

`notification-inbox-v1` 仅消费迁移 `accepted_from` 之后的事件。载荷非法、目标消失等非数据库运行期错误记录事件标识并结束消费，避免将不可恢复目标变成 RETRY/DEAD；`DataAccessException` 保留原异常以触发数据库故障重试。订阅明确包含 work_item_created/update_published/update_edited 的 v2，以及 assigned 和项目成员/负责人事件的 v1；这是对[冻结事件契约](../data/2026-08-31-work-item-event-contract-freeze.md)通知触发清单的增量，未将所有历史版本重新投递。业务引用沿用 [M3 公开端口约束](2026-08-31-m2-exit-and-m3-public-ports.md)。

HTTP 提供本人分页、分组计数、read/unread/archive 和带 `upTo` 水位的 read-all。游标绑定公司、用户、状态与分组，排序使用投影创建时间和 ID；本人状态设置天然幂等，仅要求会话与 XSRF，不增加幂等键或资源版本门槛。已归档通知执行 read 时只补 `read_at`，保留 ARCHIVED 状态及原 `archived_at`，重复 read 不改变已有已读时间；阅读归档内容不会将其移回普通列表。跨用户 ID 返回 404，响应 `Cache-Control: no-store`。水位来自数据库时钟，并裁剪未来传入时间，避免请求完成前后到达的新通知被无意标为已读。

Web 采用页面可见时每 30 秒轮询计数，并在 focus、online、visibilitychange 刷新；Electron 主窗口保持轮询。首次加载、未读水位或总数变化、跨标签页失效消息、状态修改都会刷新最新未读列表；打开收件箱另行读取当前筛选列表。这先解决站内提醒，Delivery/Attempt、SSE/ACK、管理重试与保留期仍不在本次模型内。

UP5c 为 `workitem.connection_created` v1 建立独立消费分支，先检查 `origin=CREATED`，再仅通过 `targetProjectId`、`targetWorkItemId` 获取目标项目当前 OWNER。接收人须为启用账号和目标项目有效成员，且不是操作者；目标工作项必须属于该目标项目。使用新增原因 `CONNECTION_CREATED`、既有 `WORK_ITEM` 目标与 ID 字段，不保存任何来源项目/工作项/连接/列标识或列名、标题、正文。列表、未读计数及按组 read-all 均将该原因归入 `PROJECT`。读取继续按当前主体批量鉴权，失权时清空全部目标引用；点击后的工作项读取仍独立鉴权，不把已收到通知作为权限凭据。

V63 追加扩展原因约束和投影代码约束，以数据库 `clock_timestamp()` 新建 `CONNECTION_CREATED_V1` 水位，保留 V57 的 `INBOX_V1` 水位与已有通知不变。连接事件只接受新水位之后发生的事件，部署前积压不补发；其他通知仍使用原水位。幂等重放沿用既有事件与接收人唯一键，既有事件载荷、冻结事件与通知 HTTP 属性均未扩展。

OpenAPI 的 `NotificationReason` 响应枚举新增值由用户明确批准；精确例外 `2026-10-03-connection-created-notification` 保留于 `tools/openapi/breaking-change-exceptions.json`，含已批准连接筛选 20 项上限的当前规范另追加 `2026-10-03-connection-notification-filter-limits`。相对 PR-4 dev 基线，仅移除该新增响应枚举值的临时规范通过完整 openapi-diff，确认 UP5a 的可选查询参数及上限兼容。每条例外仅绑定对应两个完整规范的 SHA-256，不修改历史例外。新旧桌面壳通过[桌面提醒](../product/2026-09-25-desktop-inbox-alerts.md)的原因能力声明隔离，连接事务与事件仍由[连接架构](2026-09-30-work-item-connections.md)拥有。

新增 workitem.work_item_assignees_changed v1 订阅，只向新增处理人投递 ASSIGNED；created 数组路径覆盖全部处理人，COMMENT 候选也包含全部当前处理人，标记与兼容规则见[多处理人决策](../data/2026-10-05-work-item-multiple-assignees.md)。

UP8 起，投影在写入 `user_notification` 前按[项目通知偏好](../product/2026-10-06-project-notification-preferences.md)过滤 MENTION、COMMENT/REPLY、ASSIGNED 与 CONNECTION_CREATED；项目成员与负责人原因始终送达。Delivery/Attempt、SSE/ACK、管理重试与保留期仍不在本模型内。

[项目删除治理](../product/2026-10-09-project-archive-and-deletion.md)通过独立 `notification-project-deletion-v1` 消费 scheduled、cancelled、reminder_due 的 v1 事件；不为永久清除事件新增收件箱通知。三种原因 `PROJECT_DELETION_SCHEDULED/REMINDER/CANCELLED` 均使用 `PROJECT` 目标并归入 `PROJECT` 组，不受项目偏好过滤。接收人仅为当前负责人和 identityaccess 公开端口返回的 ACTIVE/ENABLED 有效企业管理员；`APP_MANAGER` 已由 `CurrentActor` 继承 `COMPANY_ADMIN` 权限，受众沿用相同语义，避免拥有相同治理权限的账号漏收提醒。发起、撤销排除操作者，到期提醒不排除任何受众，也不要求管理员成为项目成员。

V69 使用数据库时钟建立独立 `PROJECT_DELETION_V1` 水位，保留原两个水位；部署前删除事件积压不补发。事件仅包含 ID 和时间，`notification_event.deletion_purge_after` 保存发起或提醒时的期限快照，撤销事件不附带期限。HTTP 增加可选可空 `deletionPurgeAfter`，失权时置空；Web 以公司时区显示日期，撤销当前计划不会抹去历史通知的原期限。项目名仍实时鉴权读取。响应枚举扩展必须由 OpenAPI 精确哈希例外约束；桌面仍只转发原因和最小人员引用，不携带项目或期限快照。

普通通知消费先通过公开上下文检查删除计划；已计划或已开始清除时不产生评论、提及、指派、成员或连接通知。catalog 的通知读取端口在 Outbox 消费事务内锁定项目行，再检查计划与清除状态；删除通知也必须取得仍未清除的当前负责人。该锁保持到投影提交，清除领取与投影相互串行，避免 `NOTIFICATION` 阶段已结束后迟到事件重新建立通知。

个人偏好 GET/PUT 在事务内先通过当前负责人公开查询保持项目共享锁，再校验 ACTIVE 成员资格和当前主体的项目可见性；归档后的普通成员、清除已开始或目标消失时返回 404。偏好属于个人通知设置，现有入口允许归档后仍可见的成员（如负责人）调整，不改变项目内容，也不能静音删除治理通知。该锁保持到偏好读取或写入提交，避免只检查旧成员资格的并发请求在 `NOTIFICATION` 阶段之后重新插入无项目外键的偏好行。

notification 自有 `ProjectDataPurger` 声明 `NOTIFICATION`、order 10。每批累计最多 500 行，先删 `user_notification`，再锁定并只删除无接收人子行的 `notification_event`，余量用于 `project_notification_preference`；返回是否仍有自有记录，支持中断后重入。父事件锁定和删除采用两条语句，第二条语句重新观察已提交子行，避免并发外键插入触发超过批次预算的级联删除。

## Alternatives considered

- 将标题与评论快照写入投影：读取更简单，但移出项目后会泄露历史业务文本，且编辑删除后难以保持一致。
- notification 直接读取其他模块表：能减少部分查询，但破坏模块边界并复制授权规则。
- 目标消失仍抛出不可重试异常：会把事件标为 DEAD，阻塞同聚合之后的事件；只有数据库故障值得重试。
- 首期引入 SSE、投递确认和持久化投递任务：实时性更好，但对便携 Electron 壳、重连和消费水位引入额外维护成本，暂选有界轮询。
- 复用 ASSIGNED 或成员变化原因：连接创建没有发生指派或成员变更，会使用户文案和分组含义失真，因此追加专用原因。
- 重置原收件箱部署水位：会丢弃其他类型尚未消费的合法事件，因此仅为连接通知新增独立水位。
- 保存来源快照或点击时直接信任通知目标：可能暴露来源信息或已撤销权限的内容，因此仅保存目标引用并沿用实时鉴权。
- 从项目当前状态推导历史删除期限：撤销或重新申请后原日期消失或被替换，因此只保存事件时间快照，并随目标权限隐藏。
- 将删除治理通知发送给所有成员或按项目偏好静音：扩大已归档业务信息的受众，或让拥有删除治理权限的负责人和管理员漏收不可恢复动作提醒，因此采用专门受众且绕过偏好。
- 直接级联删除通知事件：单个事件可有超过 500 个接收人，会突破短事务预算，因此先分批删除子行并锁定父行检查剩余子行。

## Consequences

`NotificationInboxProjectionTest` 验证 CREATED 限定、目标引用白名单、非成员/停用账号/本人/目标不匹配排除及独立部署水位；`ConnectionNotificationIT` 验证真实连接事件、仅目标负责人接收、存储隐私、去重、PROJECT 分组和成员撤销后的隐藏与拒绝打开；`ConnectionNotificationMigrationIT` 从 V62 升级，验证历史通知和水位保留、新原因约束以及迁移重跑不改变水位。`NotificationHttpIT` 验证新原因序列化、PROJECT 列表/计数/read-all、跨用户隔离及目标删除后的隐藏和工作项 404。

`ProjectDeletionNotificationProjectionTest` 覆盖删除受众、操作人排除、提醒全员、独立水位、迟到目标与数据库重试；`NotificationInboxQueryServiceTest` 覆盖期限快照和失权隐藏；`M108PlatformRoleQueryIT` 覆盖有效管理员、角色继承、公司隔离、撤销及停用账号。`ProjectDeletionNotificationMigrationIT` 从 V67 升级到 V69，覆盖旧通知/水位保留、三种原因、期限重放不变，以及超过 1200 个接收人的累计有界清理与重入。`NotificationHttpIT` 增加三种原因的真实投影、仅治理受众、偏好绕过、历史日期保留及失权隐藏；与 `NotificationPreferenceServiceTest` 一起覆盖归档成员的偏好不可访问及清除开始后不能重建偏好。

通知业务名称和摘要反映当前可见事实，删除期限保留当时的时间事实；被移出后保留通知和计数但隐藏目标及期限。它仍不承担历史审计职责。当前页读取只做固定数量批量调用，不做逐条业务查询。若并发用户与轮询读压显著增加，或产品要求低于 30 秒的提醒延迟，需要重新评估 SSE 与批量投递模型。迁移水位意味着部署前积压不补发，后续增加消费者版本也必须明确回溯边界。桌面提醒的最小文本、账号水位和偏好独立性由桌面提醒 Note 维护。
