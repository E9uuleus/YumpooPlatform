# Agent Note: Work Item 普通类别字段与项目级 Kanban rank

Status: implemented

## Problem

Work Item 原先复制 Content 工作项类型，并把 Kanban rank 划分在 Content + status 泳道。类别成为普通字段后，复制类型没有权威意义；若切换类别仍迁移 rank、编号或关联事实，就会把显示分类误当成工作项搬迁。项目总表按类别排序也需要稳定的 Keyset 游标，而不能继续复用 Content 查询配置。

本 Note 部分替代 [Work Item Core 决策](2026-08-22-work-item-core-contract.md) 中不可跨 Content、Content 级查询、`ContentSortField` 和 Content + status rank 的段落。编号、乐观锁、字段约束、状态、软删除、关系、Project 手工顺序和正文不进入事件等其余约束继续有效。

## Decision

Work Item 不再保存或公开 `type`，`content_id` 是同 Project 必填普通类别字段。创建根项和子项必须选择启用类别。`PATCH /work-items/{id}/content` 使用 Work Item 强 ETag 与幂等键；目标必须属于同 Project 且启用。相同类别返回原事实，不增版、不改审计、不发布事件。

有效切换只更新 `content_id` 和 Work Item 版本，保留 item_no、project_id、父子与普通关系、discussion、附件、`project_sort_key`、状态和 rank。当前停用类别禁止新建和切入，但不阻止其既有事项的字段编辑、状态迁移、讨论、附件、删除或恢复。

Kanban rank 的唯一性、lane 锁、分页和重平衡边界改为 Project + status。V48 以 `project_sort_key` 确定性回填 rank；类别切换不迁移 rank。项目列表使用独立 `WorkItemViewType` 与 `WorkItemSortField`，CONTENT 按类别 sortOrder 排序并以类别 ID、Work Item 稳定键打破并列。版本化游标携带 contentId 和完整排序锚点，支持升序、降序与最多三字段排序。

Work Item 列表、详情、子项和关系引用返回 `contentName/contentColorToken`，不返回类型。类别切换发布 `workitem.work_item_fields_changed@v2`，记录前后类别 ID、名称和颜色；created、fields-changed、status-changed、deleted、restored 当前生产者使用 v2，冻结 v1 不修改。

## Alternatives considered

- 类别切换时创建新 Work Item：拒绝，会改变编号并断开讨论、附件、关系和项目顺序。
- 继续保留只读 `type` 快照：拒绝，类别可改名和切换后没有稳定类型语义，快照会成为漂移真源。
- 继续以 Content + status 保存 rank：拒绝，切换类别将被迫搬迁 rank，并使项目看板没有单一排序事实。
- CONTENT 排序只按名称：拒绝，重命名会意外改变用户配置的目录顺序；sortOrder 是显式真源。
- 发生 ETag 冲突后自动重放：拒绝，仍坚持让用户基于最新事实明确重提。

## Consequences

讨论和附件的数据库行不再保存 Content 外键；定位可从 Work Item 当前类别派生，但授权事实始终是 Project 与 Work Item。类别目录的 `ever_used` 在首次创建或切入时与业务命令同事务推进，防止后来软删除历史类别。

所有新增 Work Item 查询字段必须同步扩展排序白名单、游标锚点、正反向 seek 测试、OpenAPI 和生成 SDK。任何写入 rank 的路径都必须锁 Project + status lane；任何类别写入口都不得改写 rank 或 `project_sort_key`。
