# Agent Note: Project 生命周期治理与分期 blocker 契约

Status: implemented

## Problem

Project 需要可恢复的归档和可审计的紧急治理覆盖。若各事实模块自行判断归档状态，归档和新事实写入会产生竞态；若在 Work Item、Worklog、Feedback 真源尚未具备归档语义时注册空 provider，又会把未知错误表示为“零 blocker”，形成虚假的业务保证。

## Decision

Project 只允许 `ACTIVE -> ARCHIVED` 和 `ARCHIVED -> ACTIVE`。DRAFT 不可直接归档；MAIN 单工作空间实施后 Project 不再支持跨 Workspace 迁移。冻结的 v1 迁移路径仅作为 deprecated 兼容入口保留，校验可见性、CompanyAdmin、强版本和幂等键后固定以 `INVALID_STATE_TRANSITION` 拒绝，不改变 Project。恢复保留首次 `activatedAt`，清空 `archivedAt`。归档和恢复都锁定 Project 行并使用强版本前置条件；恢复重新确认 Project 仍归属当前 Company 的 ACTIVE MAIN。

普通归档仅 ProjectOwner 可执行。CompanyAdmin 不借普通管理员读取权限绕过 Owner，而是使用显式 `PROJECT_ARCHIVE_WITH_OPEN_ITEMS` 覆盖；CompanyAdmin 独占恢复。覆盖理由只进入授权可见的 `admin_override` 与 Security Audit，不进入领域事件。覆盖记录只保存请求哈希、幂等键、安全前后快照和按 code 聚合的非负计数，不保存 blocker 对象 ID 或业务正文。历史 Workspace 覆盖记录仍可读取，但创建请求不再接受 Workspace 覆盖。

归档 blocker 由 administration 的有序收集器编排，稳定顺序为 workitem、worklog、productfeedback。生产代码维护显式声明集合；M2-10 已声明 workitem 并通过公开端口报告真实开放事项数，开放定义为未删除且状态类别为 `TODO/IN_PROGRESS`。Worklog 与 Product Feedback 尚未声明：Worklog 在 M3A-13、Feedback 在 M3B-11 接入真实 provider；三类 blocker 的总集成门禁必须在两者均完成后执行。禁止 Noop、Empty 或固定零 provider。未来某模块启用 Project 事实写入时，必须在同一变更中复用 Project `FOR SHARE` 生命周期守卫并注册真实 provider。已声明 provider 缺失、异常、来源不匹配或报告不完整时统一返回可重试 `DEPENDENCY_UNAVAILABLE`，Project 保持 ACTIVE。

Project 创建和恢复对 ACTIVE MAIN 获取共享锁。稳定 4xx Project 覆盖失败和幂等响应一同提交并可同键重放；503/500 回滚幂等占位，允许原键重试。成功覆盖的目标更新、override、Security Audit、Outbox 与幂等结果在同一事务提交。Workspace 生命周期并发语义已由 [MAIN 单工作空间契约](2026-08-23-main-workspace-contract.md) 完整替代。

## Alternatives considered

- 在 M2-08 为三类 blocker 注册永远返回零的 provider：拒绝。零是业务事实，不是“尚未接入”的安全替代。
- 让 CompanyAdmin 直接调用普通归档：拒绝。会混淆 Owner 日常权限与需要理由、历史和审计的治理越权。
- 归档后异步阻止或补偿新事实：拒绝。无法避免归档成功与新事实同时提交，必须用数据库锁顺序建立互斥。
- 迁移时复制 membership、Content 和关系：拒绝。Workspace 是 Project 归类字段，迁移只改变 `workspace_id`、Project 版本和审计时间。
- 将理由写入领域事件：拒绝。理由可能包含敏感治理上下文，事件只携带模式和安全聚合计数。

## Consequences

Work Item、Worklog、Product Feedback 模块接入 Project 写入时必须使用公开共享生命周期守卫；当模块具备可形成 blocker 的业务记录真源时，还必须在同一变更中提供真实完整的 blocker 报告。M2-10 首次交付 Work Item 真源并完成 provider 义务，已声明 provider 任一缺失都会被视为覆盖不完整而关闭失败。当前只能确认 `PPM-014-OPEN-WORK-ITEMS` 切片；Worklog、Feedback 与完整 PPM-014 仍未验证。

Activity 在 M2-20 消费新产生的 `catalog.project_archived` 与 `catalog.project_reopened`；既有 `catalog.project_moved_to_workspace` 仍作为历史事实可读取但不再产生。M2-24 完成 M2 真实切片综合回归，但不伪造尚不存在的 Worklog/Feedback provider：Project 的 Worklog blocker 与最终验收在 M3A-13，Feedback blocker 与最终验收在 M3B-11，三类总门禁在二者均完成后执行。治理历史 API 已交付，但 M2-08 不增加独立历史页面。
