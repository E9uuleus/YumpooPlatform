# Agent Note: Project 范围查询与权限契约

Status: implemented

## Problem

Project 列表、Workspace 计数、管理员治理可见性、Owner 日常命令横跨 catalog、administration、identityaccess 与 workitem。若各响应自行统计或由 Java 加载后过滤，会产生数量侧信道。

## Decision

Project 范围查询在数据库同时约束 company 与调用人的 ACTIVE membership，COMPANY_ADMIN 才可绕过 membership。页面行和总数复用完全相同的权限与筛选谓词，支持名称/编码大小写不敏感包含搜索、负责人/调用人角色多选、最后修改时间起点与生命周期；组内 OR、组间 AND。默认生命周期为 ALL（ACTIVE 与 ARCHIVED），项目管理页也显式请求 ALL，并按 name、code、id 稳定排序。Workspace `visibleProjectCount` 使用同一可见性和当前生命周期口径，不保存派生计数。

负责人筛选选项只从调用人可见的全部生命周期 Project 中提取 distinct owner，再通过 Identity & Access 最小用户快照补充显示名。仍挂在 Project 上的离职或停用负责人保持可筛选；接口不返回其他目录资料，也不泄露不可见 Project 的负责人。摘要响应包含必填 `createdAt/updatedAt`，Workspace ID/code/name 只作为内部 MAIN 归属兼容信息。

详情返回 actorAccess 和服务端能力布尔值。能力只供 UI 优化；每个命令重新鉴权。Owner 可 PATCH 名称与描述设置，管理员非成员只能读取、治理成员和重指派负责人，不能代替 Owner 修改设置；Owner 同时是管理员时走 Owner 日常路径。

项目创建直接进入 ACTIVE；类型、模板、客户字段、激活接口及原客户名称门槛由[统一项目模型](2026-09-30-unified-project-model.md)完整取代，本记录保留查询与权限决定。

PATCH 事件只记录 name/description 变更字段名；描述不复制到事件或审计摘要。

## Alternatives considered

- 先加载全部 Project 再按 membership 过滤：拒绝，分页总数和 Workspace 计数会形成侧信道。
- 在 Workspace 保存项目计数：拒绝，会复制权限相关派生事实并带来失效窗口。
- 管理员获得全部 Owner 日常能力：拒绝，治理权限不应隐式代替当前负责人。
- 保留旧激活路径作为兼容入口：拒绝，创建即 ACTIVE，生命周期兼容义务由[统一项目模型](2026-09-30-unified-project-model.md)处理。

## Consequences

所有新 Project 查询消费者必须复用相同 SQL 可见性口径；能力字段不能被当作授权凭据。M2-08 的归档/恢复和后续 Activity 投影必须延续强 ETag、重新鉴权与安全摘要约束。

产品筛选与激活时的关联产品检查由[删除产品决定](2026-09-30-remove-product-concept.md)取消；本记录继续拥有查询可见性与权限；其余旧模型决定由[统一项目模型](2026-09-30-unified-project-model.md)取代。
