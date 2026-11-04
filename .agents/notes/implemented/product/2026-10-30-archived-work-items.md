# Agent Note: 已归档工作项的查看与恢复

Status: implemented

## Problem

工作项可以归档，归档后从表格、看板和搜索中消失（`NOT archived`），只能通过详情链接恢复。成员找不到归档过的工作项，也无法一次恢复多项。

## Decision

- 项目工作项表格的工具栏加「已归档」，打开抽屉列出本项目归档的工作项；看板视图同样可用，仪表板内嵌表格不显示。
- 专用接口 `GET /projects/{projectId}/archived-work-items`：按标题或编号搜索，offset 分页，按 updated_at 倒序。归档项只读，updated_at 与 updated_by 即归档时刻与归档人，不新增 archived_at 列。列表包含单独归档的子项（带父项标题）；随已归档父项隐藏的子项不单独列出，计入父项的 subitemCount（只数未归档的子项）。
- 恢复沿用 `POST /work-items/{id}/unarchive`，批量恢复在前端逐项串行调用，失败不阻止其他项，与[表格批量操作](2026-09-25-work-item-table-batch-actions.md)一致。恢复权限与归档相同：项目进行中的负责人或成员。
- 点名称用现有详情抽屉打开，详情中也能恢复。

## Alternatives considered

- **在主表加「显示已归档」筛选**：归档行不可编辑，混在表格里要处理编辑、排序、分组、子项与连接列的各种只读分支，改动面大。
- **新增 archived_at、archived_by 列**：需要回填历史数据并登记迁移例外；归档项只读，updated_at 已经等于归档时刻。
- **后端批量取消归档接口**：恢复是低频小批量操作，串行调用保留逐项的事件与权限语义。

## Consequences

- 若将来允许修改已归档的工作项，updated_at 不再等于归档时刻，需要改为独立的归档时间列。
- 抽屉里恢复后，表格按现有的连接与父子刷新逻辑重新加载。
