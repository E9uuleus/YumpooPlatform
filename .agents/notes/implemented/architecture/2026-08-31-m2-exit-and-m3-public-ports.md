# Agent Note: M2 阶段退出、M3 公开端口与跨聚合锁序

Status: implemented

## Problem

M2 收口需要证明 Project、Work Item、关系和治理切片可以协作，并为 Worklog 提供稳定的授权读取和并发写入边界。未来模块直接读取 catalog/workitem 内部表、复用 Activity 投影查询或自行发明锁序，会绕过 actor-scoped 可见性、暴露正文与客户资料，并制造归档后写入窗口。空 provider 则把“事实源不存在”错误表示成“零 blocker”。

## Decision

M2-24 的报告保存当时真实 Spring HTTP、PostgreSQL/Testcontainers 和前端组件/路由验证结果，按历史结构校验，不再要求恢复已经退役的实现。[删除产品决定](../product/2026-09-30-remove-product-concept.md)取代其中的产品治理、关系与 M3B 规划；Worklog 的 Project 归档 blocker 仍由 M3A-13 接入，MAIN Workspace 管理 UI 和提醒调度继续各自分期。历史报告不自动成为当前功能或部署证明。

M3A Worklog 通过 `ProjectFactWriteGuard` 与 `WorkItemReferenceQuery` 接入。后者提供 actor-scoped 活动引用与 including-deleted 历史引用，最小快照只含 ID、Project/Content、itemNo、type、title、statusCode、statusCategory 和 deleted，不暴露正文、备注、处理人或客户数据。未来模块不得读取 catalog/workitem 内部表，也不得把无授权语义的 Activity 查询端口当作业务引用端口。[TimeTrackingRecordQuery](2026-09-07-work-item-time-tracking.md)补充只读计时来源，记录修正仍由 workitem 负责。

事实写入口必须先取得 Project 生命周期锁并在锁内复核可写状态，与项目归档排他锁形成互斥。新增跨聚合写入必须明确一致锁序和并发结果，不为已经删除的聚合保留守卫或锁。原产品锁序的理由与退出范围由删除产品决定保存。

兼容新增展示字段时采用可选响应字段，保护部署前已经固化的幂等响应。治理历史 action/target 继续是可演进枚举，生成客户端把未知响应值映射到 `UnknownDefaultOpenApi`；OpenAPI 门禁允许响应枚举增加值，单独检查请求方向。删除、改名或改变既有值必须有精确旧/新哈希例外，本次产品删除遵循该流程。

## Alternatives considered

- 让 Worklog 直接 JOIN Catalog 与 Work Item 表：拒绝。会破坏模块依赖方向，并绕过资源隐藏、删除状态和最小披露语义。
- 复用 Activity 查询作为引用校验：拒绝。Activity 是裁剪后的历史投影，不是当前业务事实或写入授权真源。
- 注册返回零的未实现 provider：拒绝。零是业务事实，缺少 provider 必须明确分期或失败关闭。
- 在跨聚合归档中反向加锁：拒绝。会与事实写入的稳定顺序形成死锁；原产品场景的具体理由保存在删除产品决定中。
- 将兼容新增展示字段直接改为 OpenAPI 必填：拒绝。部署前固化的幂等响应可能没有这些字段，强制必填会破坏兼容读取。
- 为了让枚举兼容门禁通过而把治理 action/target 改成无约束字符串：拒绝。契约仍需列出已知值，客户端以 unknown-enum 分支承担向前兼容。

## Consequences

M3 事实写入口必须复用公开守卫；新增 blocker 时在同一变更中声明真实 provider、覆盖失败关闭和并发测试，并提交新验证证据，不改写历史报告。公开快照扩字段须重新审查隐私和授权语义，不能为页面便利传播正文或客户数据。响应枚举保持 `UnknownDefaultOpenApi` 分支，向前兼容不构成破坏既有值的授权。`implemented` 表示代码决策落地，不代表后续 provider、PR 合并或部署已经完成。
