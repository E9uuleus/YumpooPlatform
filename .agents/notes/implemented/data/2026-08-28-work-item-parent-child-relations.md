# Agent Note: Work Item 单层父子关系与根查询语义

Status: implemented

## Problem

Work Item 需要统一承载父子、相关、阻塞、来源和重复五类普通关系，并保留方向、删除审计、幂等与 Activity。父子关系若允许任意深度，就会引入递归查询、环检测、树移动和删除恢复后的多层展示语义；这些复杂度与当前产品只需要“根项 + 一层子项”的目标不相称。

## Decision

`work_item_relation` 是普通关系唯一事实表，沿用 V43 且不新增迁移。五类关系均由应用层开放：父子固定父为 left、子为 right；RELATED 按 UUID 规范化；BLOCKS、SOURCE、DUPLICATE 按当前侧角色映射 left/right。同类型同方向的有效边唯一，不同类型可以共存。逻辑关系已存在时返回既有事实和 200，不重复写事件；解除后再次关联创建新 relationId，不提供恢复关系接口。

父子层级永久限定为两层：只有根项与其直接子项。子项不能成为父项，有子项的根项不能成为子项，子项只能有一个有效父项。该更强约束本身保证无环，因此不实现递归 DAG 扫描、任意深度树移动或递归 UI。M2-21A 的新建子项、根查询、直接子项查询和同父排序继续复用；M2-21 增加挂接既有项、原子换父和解除。

关系写命令先锁 Project，再按 UUID 顺序锁 Work Item，最后锁 Relation。同项目 Project 锁串行化跨行层级约束并与项目归档竞争保持一致。换父在一个事务内软删除旧关系、创建新关系、保存幂等结果并只发出 `workitem.work_item_parent_changed`；不额外产生一删一建两条 Activity。挂接、换父和解除不修改 Work Item 版本或 `project_sort_key`。

项目与 Content 的 Table、Kanban、筛选计数只返回没有有效 `PARENT_CHILD` 入边的根项；直接子项继续从 `/work-items/{parentId}/subitems` 查询。关系查询与候选查询使用全部同项目事项语义，包括子项；候选批量返回 `ELIGIBLE`、`REPARENT_REQUIRED` 或 `INELIGIBLE` 及稳定原因。Web 保持单层子表，不在子行增加递归入口，并在共享详情抽屉增加懒加载“关系”页签。

删除 Work Item 不级联关系。关系查询保留已删除对端的编号、标题和 `deleted=true` 占位，有写权限者仍可解除；候选搜索排除已删除事项。ACTIVE MEMBER/OWNER 可管理关系，CompanyAdmin 与归档项目只读。M2-22 已开放四类普通关系跨项目，父子关系继续永久保持同项目；双侧授权、失权裁剪、不可见端匿名占位和跨项目锁序由 [跨项目 Work Item 关系的双侧授权与不可见端脱敏](../security/2026-08-31-cross-project-work-item-relation-visibility.md) 约束。

创建复用 `workitem.work_item_relation_created` v1；解除使用 `workitem.work_item_relation_deleted` v1；换父使用 `workitem.work_item_parent_changed` v1。事件只携带关系类型、端点/项目 ID、关系 ID、版本和时间，不传播标题、正文或解除原因。同项目关系只生成一条项目 Activity 投影，并让两端 Work Item 查询都可命中。

本记录替代 [Work Item 核心契约](../architecture/2026-08-22-work-item-core-contract.md) 中“Relation 尚未落地”及原先预期未来递归防环的结论；编号、权限、并发、归档、详情、讨论、附件和 blocker 的其余决定继续有效。

## Alternatives considered

- 在 `work_item` 增加 `parent_id`：拒绝。无法统一承载其余四类关系、关系审计与软删除，也会把关系生命周期耦合进 Work Item 行。
- 允许任意深度并执行递归环检测：拒绝。产品确认永久采用两层模型；更强的本地不变量更易并发封闭，也与现有单层表格一致。
- 换父复用普通删除再创建两个公开命令：拒绝。中间状态会暂时把子项提升为根项，也会生成误导性的两条 Activity。
- 父项删除时级联删除或自动晋升子项：拒绝。两者都会在删除操作中静默改写独立 Work Item 的可见结构和历史关系。

## Consequences

备份恢复、导入和未来关系命令必须把 Work Item 与 `work_item_relation` 视为一致性集合。新增普通列表、统计或筛选入口必须明确选择根语义还是全事项语义；开放项 blocker 与详情继续使用全事项。任何未来放宽到三层以上的提案都属于推翻本决策，必须重新评估数据约束、锁序、查询、Activity 和 Web 展开模型，不能在当前接口上静默扩展。
