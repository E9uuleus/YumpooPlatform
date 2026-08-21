# M2-08 Project 生命周期治理与 Workspace 迁移

状态：已实现，当前真实能力已验收；三类跨模块 blocker 的全量业务验收延至 M2-24。

M2-08 交付严格的 Project `ACTIVE -> ARCHIVED`、`ARCHIVED -> ACTIVE` 与 DRAFT/ACTIVE Workspace 迁移。归档锁定 Project 行；未来 Project 事实写入使用公开共享生命周期守卫。迁移和恢复对目标 ACTIVE Workspace 获取共享锁，Workspace 普通归档则先锁 Workspace，再拒绝仍有 DRAFT/ACTIVE Project 的目标，从而消除创建、迁入与归档竞态。

普通归档仅 ProjectOwner 可执行。CompanyAdmin 通过显式治理覆盖归档，并独占恢复和迁移。所有写命令要求 CSRF、`If-Match` 与 `Idempotency-Key`；稳定 4xx 覆盖失败会与安全失败记录一起固化并支持同键重放，503/500 不固化为业务结果。V28 `admin_override` 仅保存理由、安全前后快照、请求哈希、幂等键与 blocker 聚合计数，不保存业务正文或对象 ID 列表。

blocker 协议冻结 `OPEN_WORK_ITEMS`、`PENDING_WORKLOG_APPROVALS`、`OPEN_PRODUCT_FEEDBACK` 和 Workspace 的 `CURRENT_PROJECTS`。生产覆盖集合当前显式为空，因为 Work Item、Worklog、Product Feedback 尚无可用于归档判断的权威 provider；不创建 Noop 或零值 blocker。未来事实源启用写入时必须在同一变更中注册 provider，provider 缺失、异常或不完整统一关闭失败为可重试 503。三类真实数据验收继续由 M2-24 完成，不能据本切片标记 PPM-014 全量 `Verified`。

OpenAPI、生成 TypeScript SDK、三类 Project v1 事件、项目概览生命周期组件、治理覆盖历史 API 及安全 blocker 展示已同步。Activity 投影继续由 M2-20 消费事件；本切片不新增独立 Workspace 管理台或治理历史页面。

验收命令：

```powershell
pnpm verify:m2-08
```
