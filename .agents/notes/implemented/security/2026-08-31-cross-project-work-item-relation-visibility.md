# Agent Note: 跨项目 Work Item 关系的双侧授权与不可见端脱敏

Status: implemented

## Problem

普通 Work Item 关系需要跨越 Project 边界，但关系事实同时引用两个权限域。若只按当前事项所在 Project 授权，单侧成员就能写入、解除或枚举另一侧事实；若先分页再隐藏，`totalElements`、关系类型、relationId、ETag 或对端摘要仍会成为权限探针。CompanyAdmin 的公司级可见性也不能被误当成项目写权限。

## Decision

`RELATED`、`BLOCKS`、`SOURCE`、`DUPLICATE` 可以跨项目，`PARENT_CHILD` 永久保持同项目。跨项目创建和解除要求行为人在两侧均有 ACTIVE membership，且项目访问级别为 OWNER 或 MEMBER；两侧 Project 均须为 DRAFT 或 ACTIVE。仅凭 COMPANY_ADMIN 看到项目时保持只读，任一侧归档都以 `PROJECT_ARCHIVED` 拒绝写入。

关系表仍是对端 Project ID 的唯一发现来源。workitem 模块只把这些 ID 批量交给 catalog 的公开、actor-scoped 访问快照端口，不读取 membership 表。查询在分页与计数前排除不可见对端；`items` 与 `totalElements` 只描述可见关系。存在至少一条隐藏关系时仅返回不带数量和类型的 `hasHiddenRelations=true`，且该信号忽略 `relationType` 过滤。逐条返回的关系必定对端可见，兼容字段 `counterpartVisible` 固定为 true，`counterpart` 必定非空。

已知 relationId 也不构成授权。关系 GET 前置检查、强 ETag 解析和解除都先验证双侧可见性，任一侧失权统一返回 404，不返回关系行、类型、版本或对端字段。创建先验证目标 Project，再使用 Project 约束查询目标 Work Item；不可见 Project 或不属于目标 Project 的事项均为 404。

写事务按 Project UUID、各 Project 内 Work Item UUID、Relation 的顺序获取锁；同项目只锁一次。事务内重新取得两侧访问和生命周期快照，避免相反方向并发创建时死锁，也让归档与 membership 变化不能绕过预检。沿用 V43 和关系事件 v1：事件已有双侧 Project/Work Item ID；Activity 分别在两侧投影，每一侧投影不保存另一侧事项 ID。

本记录部分更新 [Work Item 单层父子关系与根查询语义](../data/2026-08-28-work-item-parent-child-relations.md)：该记录继续拥有两层父子和关系事实模型，本记录拥有跨项目授权、隐私裁剪和锁序扩展。

## Alternatives considered

- 让 workitem 直接查询 `project_membership`：拒绝。会破坏 catalog 的模块边界，并复制 owner、member、CompanyAdmin 和生命周期语义。
- 返回隐藏关系行但把 `counterpart` 置空：拒绝。relationId、类型、数量、时间和 ETag 本身都能泄露隐藏范围的协作事实。
- 按 `relationType` 计算隐藏占位：拒绝。调用者可逐类型探测隐藏关系的分类。
- 仅在命令入口检查双侧权限：拒绝。预检与写入之间可能发生 membership 移除或项目归档，且无法封闭相反方向并发锁序。
- 升级关系事件版本或新增数据库迁移：拒绝。V43 已存双侧 Project ID，v1 事件已经携带投影所需的全部标识。

## Consequences

所有新的关系读取入口都必须在分页和计数前应用双侧 Project 可见性，不能通过缓存、统计、导出或错误信息重新暴露隐藏关系。所有新的跨项目关系写命令必须复用同一锁序和双侧事务内复检。Web 只能把匿名占位渲染为单一不可操作提示；跨项目对端必须切换到目标 Project 上下文后再加载成员、Content 和编辑能力。

备份、恢复和事件重放继续把关系与双侧 Work Item 视为一致性集合。Linux CI 的 PostgreSQL HTTP 集成测试是数据库与并发结论的最终门禁；当前 Windows 宿主的 Java HttpClient 回环限制不得被解释为集成测试通过。
