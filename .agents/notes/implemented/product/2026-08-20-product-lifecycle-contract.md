# Agent Note: Product 生命周期与唯一负责人契约

Status: implemented

## Problem

Product 是跨 Project、反馈和治理流程长期存在的业务主数据。若负责人同时复制进平台角色表，或列表先读取全量再在 Java 中裁剪，会形成相互冲突的授权事实和可见性泄露。M2-03 交付时 Product–Project 与 Feedback 真源尚不存在，因此归档编排还必须区分当前可验证约束和后续才可能成立的 blocker。

## Decision

`catalog.product.owner_user_id` 是 Product 负责人的唯一事实源；`identityaccess.platform_role_assignment` 不保存 `PRODUCT_OWNER`。负责人和全部审计用户通过 `(user_id, company_id)` 外键绑定同一 Company，创建、恢复和重指派还要求负责人当前同时为 `ACTIVE + ENABLED`。

Product 使用 Company 内唯一、创建后不可变的稳定 code。名称与可空描述通过完整可变快照 PATCH 更新；规范化后无变化时不增加版本、不写事件。ARCHIVED Product 继续对其负责人和 COMPANY_ADMIN 可见，但禁止资料更新；归档 Product 可先由 COMPANY_ADMIN 重指派有效负责人，再恢复。

列表和详情都在 SQL 中先按 Company 与主体范围裁剪。COMPANY_ADMIN 可见全部 Product；负责人可见自己负责的 ACTIVE 和 ARCHIVED Product；其他成员得到空分页，不可见详情返回 404。ProductOwner 可更新和正常归档自己的 Product，COMPANY_ADMIN 可治理全部 Product；恢复和负责人重指派仅允许 COMPANY_ADMIN。

负责人重指派由 `administration` 在单一持久化幂等事务内编排身份校验、Catalog 条件更新、Security Audit、Outbox 和重放结果。身份离职、返岗、禁用、启用以及 Product 重指派、归档事件驱动 `OWNER_MISSING + PRODUCT` 治理投影；同一身份事件可为多个 ACTIVE Product 打开独立问题，双状态重新有效、重指派或归档时解析。告警不改变 Product 生命周期，也不自动提升其他成员。

M2-03 不创建 ProductProjectLink、Feedback 或临时 blocker。M2-24 已在 `administration` 接入真实 Product–Project blocker：有效 DEVELOPMENT/SUPPORT 关系所指向的不同 ACTIVE Project 以 `ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS` 聚合计数，普通归档因此返回 `PRODUCT_ARCHIVE_BLOCKED`；CompanyAdmin 只能通过带 10–500 字理由的显式 `PRODUCT_ARCHIVE_WITH_BLOCKERS` 覆盖。覆盖保存安全前后快照和聚合计数，不改写 Project 或关系事实。Feedback 真源仍不存在，因此只保留 source 枚举，不声明、不注入空 provider；该 blocker 延期到 M3B-11。

Product 归档事务在完成主体可见性与授权复核后排他锁 Product，再校验强版本、状态并收集 blocker；Product 事实写入通过 `ProductFactWriteGuard` 获取共享锁并复核 ACTIVE。Product–Project 关系写入先锁 Project，再按 UUID 顺序锁相关 Product；Product 归档不反向锁 Project。`catalog.product_archived` v1 兼容新增可选 `mode/blockers`，新生产者始终输出，治理理由仍只进入 Security Audit 和 override 记录。负责人治理的 Activity 投影由 M2-20 消费现有事件。

## Alternatives considered

- 把 `PRODUCT_OWNER` 写入平台角色表：拒绝。平台角色与资源唯一负责人生命周期不同，会制造第二事实源并破坏重指派原子性。
- 先读取 Company 全量 Product 再在应用层过滤：拒绝。分页总数、排序窗口和按 ID 查询都会泄露不可见资源。
- 归档后对原负责人隐藏 Product：拒绝。负责人需要读取历史主数据和关联事实，生命周期状态本身不撤销该资源范围。
- 在 M2-03 预建 Product–Project 或 Feedback 占位关系：拒绝。没有真实写入者与业务事实时，所谓 blocker 永远为空，只会制造虚假完成信号。
- 负责人失效时自动选择其他成员：拒绝。系统没有可靠的业务继任依据，自动提升会扩大权限且丢失治理责任链。

## Consequences

客户端必须保存 Product ID 与强 ETag，PATCH 发送完整 `name/description` 快照，所有生命周期命令发送 `If-Match`，创建、归档、恢复和重指派发送 UUID 幂等键。恢复若当前负责人不可用，返回 `409 INVALID_STATE_TRANSITION` 且 `details.reason=OWNER_MISSING`；创建或重指派目标无效返回 `422 ownerUserId/INVALID_OWNER`。

跨模块消费者只使用 `ProductSnapshotQuery`、生命周期/负责人条件命令端口、`ProductOwnerScopeQuery` 与 `ProductFactWriteGuard`，不得读取 Catalog 表或复制负责人展示资料。五类 v1 Product 事件不携带描述正文或治理理由正文。M2-24 已验证 Product 活动研发/支持 Project blocker、覆盖归档、恢复和并发锁序，并保持现有资源路径；未关闭 Feedback 的 PPM-015 切片仍必须由 M3B-11 的真实 provider 和最终验收补齐，不能以预留枚举或静态零计数冒充通过。
