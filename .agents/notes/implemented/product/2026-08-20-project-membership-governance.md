# Agent Note: Project 成员与唯一负责人治理

Status: implemented

## Problem

Project membership、唯一负责人、企业管理员治理、身份有效状态和审计事件跨越 catalog、identityaccess、administration。若负责人角色复制到 identityaccess、移除后新建 membership，或先改 owner 再补 membership，会产生双重真源、丢失版本连续性或在并发提交时暴露无有效负责人的 Project。

## Decision

`catalog.project_membership` 是成员关系唯一真源，`project.owner_user_id` 是负责人唯一真源。首次加入创建唯一 `(project_id,user_id)` 行；REMOVED 重激活复用原行、递增 rowVersion、刷新加入 actor/时间并清空移除字段。历史理由只保留在 Security Audit/Outbox，不从成员 DTO 返回。

所有成员写和负责人重指派固定按 Project → membership 加锁。负责人重指派先创建或重激活新负责人的 ACTIVE membership，再以 Project 强 ETag 条件更新唯一 owner；旧负责人继续是 ACTIVE 普通成员。V21 延迟约束在事务提交时验证 owner 必有 ACTIVE membership，当前 owner 的普通移除固定返回状态冲突。

成员列表允许 ACTIVE member 或同企业 COMPANY_ADMIN 读取，默认 `status=ALL`；SQL 在可见性查询阶段同时约束 company 与 ACTIVE membership，不加载后隐藏。候选查询仅 Owner 或 COMPANY_ADMIN，可搜索同企业 ACTIVE+ENABLED 用户并批量补充 membership 状态与 ETag，不返回邮箱、手机号或部门信息。Owner 的成员增删理由可省略；非 Owner COMPANY_ADMIN 必须提交 10–500 字理由，Owner+管理员按 Owner 路径处理。

写命令由 `administration.application.ProjectMembershipGovernanceService` 编排 identity 最小快照、catalog 端口、Security Audit、Outbox 与幂等结果。新建 membership 返回 201；重激活返回 200 且要求 membership `If-Match`；不同幂等键重复加入 ACTIVE 返回 409；相同幂等键重放原始状态、body 与 ETag。进入命令处理后的失败治理动作使用独立 FAILED Audit，审计不可写时失败关闭。

公开事件固定为 `catalog.project_member_added`、`catalog.project_member_removed`、`catalog.project_owner_reassigned` v1。重指派需要创建或重激活 membership 时额外发出 `changeSource=OWNER_REASSIGNMENT` 的 member-added；事件不携带 reason、客户字段或目录资料。

OWNER_MISSING 是 administration 投影，不是 Project 生命周期。负责人离职或禁用会为其 DRAFT/ACTIVE Project 幂等打开告警；返聘或启用后仅在就业和账号双状态都恢复时解决；重指派后按当前新负责人状态重算。系统不自动提升成员，也不改变 Project lifecycle。

M2-05 不实现 Project 列表、详情、PATCH、激活、Workspace 可见计数、Vue 页面、Activity 投影、归档治理或 Worklog 审批人迁移；这些边界保留给 M2-06、M2-08、M2-20 与 M3A-09。

## Alternatives considered

- 删除 membership 后重新插入：拒绝，会破坏唯一身份与 ETag 连续性。
- 用 PROJECT_OWNER 平台角色表达负责人：拒绝，会复制 catalog scoped fact。
- 先更新 owner 再异步加入成员：拒绝，会违反提交时 owner membership 不变量。
- 管理员直接读取 identity 目录 DTO：拒绝，成员 API 只暴露最小展示快照。
- 离职时自动提升最早成员：拒绝，治理决策必须由 COMPANY_ADMIN 显式完成并审计。

## Consequences

客户端必须区分新加入与重激活，并为重激活、移除和重指派分别携带 membership/Project 强 ETag。成员历史的业务解释来自 Audit/Outbox，而不是 membership 行的多版本副本。后续 Project API、Activity 和 Worklog 治理必须复用这些事实与事件，不得引入第二份 owner/membership 状态。
