# Agent Note: M2 阶段退出、M3 公开端口与跨聚合锁序

Status: implemented

## Problem

M2 收口需要同时证明已有 Project、Work Item、关系和 Product 治理切片可以协作，并为尚未实现的 Worklog 与 Product Feedback 提供稳定的授权读取和并发写入边界。若未来模块直接读取 catalog/workitem 内部表、复用 Activity 的投影查询，或各自发明锁顺序，会绕过 actor-scoped 可见性、暴露正文和客户信息，并制造 Product 归档与新关系/Feedback 事实并发提交的窗口。另一方面，为了让 M2 看似完成而注册空 provider，会把“事实源不存在”错误表示为“零 blocker”。

## Decision

M2-24 以阶段收口而非未来模块占位为完成定义。已实现切片通过真实 Spring HTTP、PostgreSQL/Testcontainers 和前端组件/路由门禁回归；Worklog 的 Project 归档 blocker 正式延期到 M3A-13，Product Feedback 的 Project/Product 归档及关系解绑 blocker 延期到 M3B-11。Project 三类 blocker 总集成门禁只在 M3A-13 与 M3B-11 均完成后运行。MAIN Workspace 管理 UI 属于 M5-07，Product Owner 重指派 UI 属于 M5-09 且保留现有 API，提醒调度属于 M4。

M3A Worklog 只能通过 `ProjectFactWriteGuard` 与 `WorkItemReferenceQuery` 接入；M3B Product Feedback 只能通过 `ProjectFactWriteGuard`、`ProductFactWriteGuard`、`ProductProjectRelationQuery` 与 `WorkItemReferenceQuery` 接入。`WorkItemReferenceQuery` 提供 actor-scoped 活动引用与 including-deleted 历史引用，最小快照只含 ID、Project/Content、itemNo、type、title、statusCode、statusCategory 和 deleted，不暴露正文、备注、处理人或客户数据。`ProductFactWriteGuard.lockForFactWrite` 返回 Product ID、Company ID、code、status 与 ownerUserId 的最小共享锁快照，隐藏资源继续返回 404。未来模块不得读取 catalog/workitem 内部表，也不得把无授权语义的 Activity 查询端口当作业务引用端口。

跨聚合写入的稳定锁序为“Project → 按 UUID 排序的 Product”。创建 Product–Project 关系、设为主关系及未来 Feedback 事实先取得 Project 生命周期锁，再按 UUID 排序取得 Product 共享锁并复核 ACTIVE；Project 激活在自身排他锁内按同样顺序锁定 DEVELOPMENT/SUPPORT Product。Product 归档只排他锁 Product 后读取 blocker 计数，不反向锁 Project。因此关系或激活先完成时归档必然看到 blocker；归档先完成时后续写入等待并因 Product 已 ARCHIVED 失败。

Product 响应兼容增加可选 `ownerDisplayName`、`etag` 与 `capabilities`；部署后的新请求总是返回，部署前固化的幂等响应仍合法。`ProductCapabilities` 包含 `canUpdate`、`canArchive`、`canRestore`、`canOverrideArchive` 与 `canReassignOwner`，服务端仍执行最终授权。Product 归档事件 v1 只增加可选 `mode/blockers`，治理理由不进入领域事件。治理历史的 action/target 属于可演进枚举，生成 TypeScript 客户端把未知响应值映射到 `UnknownDefaultOpenApi`；因此 OpenAPI 兼容门禁允许响应枚举增加值，但仍禁止删除、改名或改变已有值，并继续单独检查请求方向。

## Alternatives considered

- 让 Worklog/Feedback 直接 JOIN Catalog 与 Work Item 表：拒绝。会破坏模块依赖方向，并绕过资源隐藏、删除状态和最小披露语义。
- 复用 Activity 查询作为引用校验：拒绝。Activity 是裁剪后的历史投影，不是当前业务事实或写入授权真源。
- 在 M2-24 注册返回零的 Worklog/Feedback provider：拒绝。零是业务事实，缺少 provider 必须保持明确延期或失败关闭。
- Product 归档同时锁 Project：拒绝。会形成 Product→Project 的反向锁序，与关系写入和 Project 激活产生死锁风险。
- 将 Product 展示字段改为 OpenAPI 必填：拒绝。部署前已经固化的幂等响应可能没有这些字段，强制必填会破坏兼容读取。
- 为了让枚举兼容门禁通过而把治理 action/target 改成无约束字符串：拒绝。契约仍需列出已知值，客户端以 unknown-enum 分支承担向前兼容。

## Consequences

任何 M3 事实写入口都必须复用上述公开守卫与 Project→Product 锁序；新增 blocker 时需在同一变更中声明真实 provider、覆盖失败关闭和并发测试，并更新阶段证据。公开引用快照若扩字段必须重新审查隐私和授权语义，不能为了页面便利传播正文或客户数据。任何可演进响应枚举都必须保留生成客户端的 `UnknownDefaultOpenApi` 分支，门禁放宽不等于允许破坏已有枚举值。M2-24 证据必须把已验证切片和正式延期切片分开，Linux PR CI 全绿后才能把本分支的实现状态提升为已合并事实；本 Note 的 `implemented` 表示代码决策已落地，不表示尚未发生的 M3 provider、PR 合并或部署已完成。
