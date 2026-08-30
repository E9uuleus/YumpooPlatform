# Agent Note: Work Item 单层父子关系与根查询语义

Status: implemented

## Problem

Work Item 核心原先没有关系事实，项目与 Content 的 Table/Kanban 会把所有事项平铺展示。产品关系基线又要求 `PARENT_CHILD` 同项目、单父和无环；如果仅在前端保存嵌套或把父 id 直接塞入 Work Item，会产生第二真源，难以继续承载 RELATED、BLOCKS、SOURCE 和 DUPLICATE，也无法保留关系删除审计。

## Decision

M2-21A 以 `work_item_relation` 作为普通关系的唯一事实表，父项为 left、子项为 right。表结构预留五类关系并保存两侧 Project 快照、创建审计、软删除事实和 `row_version`；数据库约束同企业、禁止自关联、活动关系对唯一、活动子项最多一个父项，并要求 `PARENT_CHILD` 两侧同 Project。本切片的应用层只创建 `PARENT_CHILD`。

子项命令只能在根工作项下创建全新事项，并在一个事务内写入 Work Item、关系、幂等结果、`workitem.work_item_created` 与 `workitem.work_item_relation_created`。这个受限入口天然无环；挂接既有事项、换父、解除关系和递归环检测仍属于 M2-21。子项可选择父项所属 Project 的任一 ACTIVE Content，类型继续由 Content 派生。

项目与 Content 的 Table、Kanban、筛选计数只返回没有活动 `PARENT_CHILD` 入边的根事项。直接子项只从 `/work-items/{parentId}/subitems` 查询，项目列表项批量携带未删除直接子项数，禁止逐行计数。子项仍是完整 Work Item，可通过详情地址访问、参与开放项 blocker，并复用字段编辑、状态、讨论、附件、删除恢复和强 ETag 规则。

删除 Work Item 不级联删除关系，也不把子项晋升为根项。删除的子项不计数；恢复后重新出现在原父项下。父项删除期间子项仍不进入根查询，父项恢复后恢复嵌套。项目手工排序与每个父项的子项排序使用同一稀疏键事实，但移动命令分别限制为根锚点或同父直接兄弟锚点。

Web 只展示一层 Monday 形态子表：所有根行都有可访问的展开按钮，首次展开懒加载，折叠保留缓存；子表共享主表列偏好、编辑能力和详情入口，但维护独立的加载、错误、选择、排序、保存与快速新增状态，且子行不再提供递归展开入口。

本记录部分替代 [Work Item 核心契约](../architecture/2026-08-22-work-item-core-contract.md) 中“Relation 尚未落地”和项目列表包含全部非删除事项的旧结论；编号、权限、并发、归档、详情、讨论、附件和 blocker 的其余决定继续有效。

## Alternatives considered

- 在 `work_item` 增加 `parent_id`：拒绝。无法统一承载其余四类关系、关系审计与软删除，也会把关系生命周期耦合进 Work Item 行。
- 在前端视图配置保存子项：拒绝。关系会变成不可审计的 UI 偏好，API、blocker、Activity 和其他客户端无法得到同一事实。
- M2-21A 同时开放挂接既有事项和任意深度：拒绝。它要求递归环检测、换父并发与解除/恢复语义；当前用户价值可由“新建直接子项”闭合交付。
- 父项删除时级联删除或自动晋升子项：拒绝。两者都会在删除操作中静默改写独立 Work Item 的可见结构和历史关系。

## Consequences

备份恢复、导入和未来关系命令必须把 Work Item 与 `work_item_relation` 视为一致性集合。新增普通列表、统计或筛选入口必须明确选择根语义还是全事项语义；开放项 blocker 与详情仍使用全事项。未来 M2-21 挂接既有事项时必须在数据库约束之外增加事务内递归环检测，并延续 Project→Content→Work Item→Relation 的确定锁序。关系事件只能兼容增加字段，不能传播正文或不可见端敏感快照。
