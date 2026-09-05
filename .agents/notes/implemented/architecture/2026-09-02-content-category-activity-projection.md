# Agent Note: 工作项类别切换的 Activity 投影

Status: implemented

## Problem

Activity v1 与单元格动态原先把 Content 当成不可变类别快照，并明确不存在跨 Content 移动。类别成为普通可编辑字段后，需要展示类别前后值；同时冻结的 v1 事件不能被改写，原始事件仍不得把正文或整个领域对象复制进审计读模型。

本 Note 部分替代 [Activity 投影决策](2026-08-30-activity-projection-contract.md) 中“类别不产生变更”的结论；追加投影、迁移切点、当前权限复核、显示名快照、游标和敏感字段白名单继续有效。

## Decision

Activity 消费者同时接受冻结 v1 和当前 v2。v1 按历史 schema 原样裁剪；v2 的 created、fields-changed、status-changed、deleted、restored 不读取 `workItemType`。Content 生命周期使用 created、updated、deleted v2。

类别切换复用 `workitem.work_item_fields_changed@v2`，`changedFields` 包含 `contentId`，载荷提供前后类别 ID、名称和颜色。综合 Activity 保存可展示的类别前后摘要；工作项单元格投影增加 CONTENT 列事件，结构化值只保存 ID、名称和颜色。正文、备注、内部 rank、目录版本及完整 Content 对象仍不进入投影。

同值类别请求不发布事件，因此不会产生空 Activity。标签或类别后续改名、改色不重写历史投影；历史展示使用事件发生时快照。读取仍按 Work Item 当前 Project 权限复核，类别停用或切换不改变 Activity 可见性。

## Alternatives considered

- 为类别切换新增专用事件名：拒绝，字段变更 v2 已能表达前后结构值，新增事件会重复 Activity 语义。
- 修改冻结 fields-changed v1 的必填字段：拒绝，会破坏 M2-23 历史契约和已存样例。
- 投影查询时 JOIN 当前 Content 名称与颜色：拒绝，会改写历史含义并让已删除/改名类别无法解释。
- 把全部 v2 payload 原样保存：拒绝，会扩大敏感字段和未来 schema 演进的耦合面。

## Consequences

新增字段进入 Activity 仍需显式加入事件 schema、生产者前值、综合投影白名单、单元格结构值、摘要渲染与测试。冻结 v1 文件和归档 Note 不随术语重构而修改；事件兼容与库存审计同时验证 v1 保留和 v2 增量。
