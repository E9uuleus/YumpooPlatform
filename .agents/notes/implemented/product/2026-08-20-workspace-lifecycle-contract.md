# Agent Note: Workspace 生命周期契约

Status: implemented

## Problem

Workspace 是 Company 内项目导航和归类的稳定入口。只提供创建与列表会让客户端无法可靠刷新单个资源、无法恢复误归档项，也会诱导调用方把名称、成员或权限误当成 Workspace 身份。M2-02 交付时 Project 尚无事实表，因此还需要在不虚构项目授权事实的前提下冻结可兼容扩展的响应形状。

## Decision

`catalog` 是 Workspace 唯一事实拥有者。Workspace 只保存 Company 内稳定的调用方编码、名称、可空描述、排序、生命周期和行版本，不保存成员、角色或授权事实。

Workspace 编码由调用方显式提供，采用 Company 内唯一的 2–32 位大写稳定编码；编码创建后不可修改，也不从名称生成。名称、描述和排序通过完整可变快照 PATCH 更新，PATCH 不接受 `code`、`status` 或 `rowVersion`。规范化后无变化的 PATCH 返回原资源和原 ETag，不写库、不递增版本、不产生事件。

列表默认只返回 ACTIVE Workspace，供同 Company 的有效成员作为导航目录使用。`ARCHIVED` 和 `ALL` 筛选仅对 COMPANY_ADMIN 开放。详情 GET 对同 Company 成员公开 ACTIVE 资源；ARCHIVED 详情仅 COMPANY_ADMIN 可见，其他成员得到隐藏式 404。详情和所有写响应返回基于 `rowVersion` 的强 ETag。

归档和恢复是独立的幂等命令，分别只允许 `ACTIVE -> ARCHIVED` 和 `ARCHIVED -> ACTIVE`。它们要求 `If-Match` 与 `Idempotency-Key`，不要求 reason，也不级联修改其他业务事实。

Workspace 响应从 M2-02 起包含 `visibleProjectCount`。在 Project 真源与项目权限过滤交付前，该字段固定为 `0`；真实可见项目计数由 M2-06/M2-24 接管。`WorkspaceSnapshotQuery` 只向其他后端模块暴露同 Company、ACTIVE Workspace 的最小快照，以便后续 Project 创建编排拒绝归档目标。

## Alternatives considered

- 只提供列表而不提供详情 GET：拒绝。客户端无法用稳定资源 URL 和 ETag 刷新单项，隐藏式 404 也无法统一表达跨 Company 与归档可见性。
- 只允许归档、不允许恢复：拒绝。误操作只能通过绕过领域约束的数据库修复解决，且客户端无法形成完整生命周期。
- 使用部分 PATCH：拒绝。可空描述无法可靠区分“未提供”和“显式清空”，并增加客户端与幂等哈希的歧义；完整可变快照更容易校验和重放。
- 从名称自动生成 code：拒绝。名称可本地化且可修改，会把展示文本错误地变成跨系统身份。
- 在 M2-02 提前创建 Project 表或缓存项目计数：拒绝。那会制造第二事实源并提前绑定尚未交付的权限模型。
- 为 Workspace 单独建立成员或角色表：拒绝。Workspace 当前是导航归类，不是授权边界；授权事实仍由 Identity & Access 和后续资源权限模型拥有。

## Consequences

调用方必须在创建时选择长期稳定的 code，并保存返回的资源 ID 与 ETag。管理员客户端必须发送完整的 `name`、`description` 和 `sortOrder` 快照；清空描述时显式发送 `null`。

列表保持无分页并按 `sortOrder,name,id` 稳定排序。若未来规模要求分页，需要单独作兼容契约决策。Project 创建在接入 `WorkspaceSnapshotQuery` 后必须拒绝 ARCHIVED Workspace；已有项目占用检查、跨 Workspace 移动和真实项目可见性仍分别由 M2-08、M2-24 交付。

创建、更新、归档和恢复均产生不含完整描述正文的 v1 领域事件。普通 Workspace 导航治理不写 Security Audit；Activity 投影在 M2-20 消费这些事件。
