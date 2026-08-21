# Agent Note: Project 生命周期治理与分期 blocker 契约

Status: implemented

## Problem

Project 需要可恢复的归档、跨 Workspace 迁移和可审计的紧急治理覆盖。若各事实模块自行判断归档状态，归档和新事实写入会产生竞态；若在 Work Item、Worklog、Feedback 真源尚未具备归档语义时注册空 provider，又会把未知错误表示为“零 blocker”，形成虚假的业务保证。

## Decision

Project 只允许 `ACTIVE -> ARCHIVED` 和 `ARCHIVED -> ACTIVE`。DRAFT 不可直接归档；DRAFT/ACTIVE 可迁移到同 Company 的其他 ACTIVE Workspace；ARCHIVED 不可迁移。恢复保留首次 `activatedAt`，清空 `archivedAt`。归档、恢复和迁移都锁定 Project 行并使用强版本前置条件。

普通归档仅 ProjectOwner 可执行。CompanyAdmin 不借普通管理员读取权限绕过 Owner，而是使用显式 `PROJECT_ARCHIVE_WITH_OPEN_ITEMS` 覆盖；CompanyAdmin 独占恢复和迁移。覆盖理由只进入授权可见的 `admin_override` 与 Security Audit，不进入领域事件。覆盖记录只保存请求哈希、幂等键、安全前后快照和按 code 聚合的非负计数，不保存 blocker 对象 ID 或业务正文。

归档 blocker 由 administration 的有序收集器编排，稳定顺序为 workitem、worklog、productfeedback。生产代码维护显式声明集合；M2-08 集合为空，因为三类真源尚未提供权威归档报告。禁止 Noop、Empty 或固定零 provider。未来某模块启用 Project 事实写入时，必须在同一变更中复用 Project `FOR SHARE` 生命周期守卫并注册真实 provider。已声明 provider 缺失、异常、来源不匹配或报告不完整时统一返回可重试 `DEPENDENCY_UNAVAILABLE`，Project 保持 ACTIVE。

普通 Workspace 归档锁定 Workspace 并拒绝 DRAFT/ACTIVE Project；Project 创建、迁入和恢复对 ACTIVE Workspace 获取共享锁。稳定 4xx 覆盖失败和幂等响应一同提交并可同键重放；503/500 回滚幂等占位，允许原键重试。成功覆盖的目标更新、override、Security Audit、Outbox 与幂等结果在同一事务提交。

## Alternatives considered

- 在 M2-08 为三类 blocker 注册永远返回零的 provider：拒绝。零是业务事实，不是“尚未接入”的安全替代。
- 让 CompanyAdmin 直接调用普通归档：拒绝。会混淆 Owner 日常权限与需要理由、历史和审计的治理越权。
- 归档后异步阻止或补偿新事实：拒绝。无法避免归档成功与新事实同时提交，必须用数据库锁顺序建立互斥。
- 迁移时复制 membership、Content 和关系：拒绝。Workspace 是 Project 归类字段，迁移只改变 `workspace_id`、Project 版本和审计时间。
- 将理由写入领域事件：拒绝。理由可能包含敏感治理上下文，事件只携带模式和安全聚合计数。

## Consequences

未来 Work Item、Worklog、Product Feedback 模块接入 Project 写入时承担双重义务：使用公开共享生命周期守卫，并提供真实完整的 blocker 报告；任一缺失都会被视为覆盖不完整而关闭失败。M2-08 只能证明当前真实生命周期、Workspace 占用互斥和 blocker 协议，不能证明 PPM-014 的三类数据场景。

Activity 在 M2-20 消费 `catalog.project_archived`、`catalog.project_reopened` 和 `catalog.project_moved_to_workspace`；三类真实 provider 与全量 PPM-014 验收在 M2-24 完成。治理历史 API 已交付，但 M2-08 不增加独立历史页面或 Workspace 管理台。
