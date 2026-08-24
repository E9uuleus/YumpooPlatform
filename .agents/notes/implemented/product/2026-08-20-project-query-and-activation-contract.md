# Agent Note: Project 范围查询与激活契约

Status: implemented

## Problem

Project 列表、Workspace 计数、管理员治理可见性、Owner 日常命令和模板退役横跨 catalog、administration、identityaccess、templateworkflow 与 workitem。若各响应自行统计或由 Java 加载后过滤，会产生数量侧信道；若模板退役阻断既有草稿，会让合法 Project 永久无法进入 ACTIVE。

## Decision

Project 范围查询在数据库同时约束 company 与调用人的 ACTIVE membership，COMPANY_ADMIN 才可绕过 membership。页面行和总数复用完全相同的权限与筛选谓词，支持名称/编码大小写不敏感包含搜索、项目类型/负责人/调用人角色多选、最后修改时间起点、生命周期与 Product；组内 OR、组间 AND。默认生命周期仍是 DRAFT+ACTIVE，项目管理页显式请求 ALL，并按 name、code、id 稳定排序。Workspace `visibleProjectCount` 使用同一可见性和当前生命周期口径，不保存派生计数。

负责人筛选选项只从调用人可见的全部生命周期 Project 中提取 distinct owner，再通过 Identity & Access 最小用户快照补充显示名。仍挂在 Project 上的离职或停用负责人保持可筛选；接口不返回其他目录资料，也不泄露不可见 Project 的负责人。摘要响应包含必填 `createdAt/updatedAt`，Workspace ID/code/name 只作为内部 MAIN 归属兼容信息。

详情返回 actorAccess 和四个能力布尔值。能力只供 UI 优化；每个命令重新鉴权。Owner 可 PATCH 设置并激活，管理员非成员只能读取、治理成员和重指派负责人，不能代替 Owner 修改设置或激活；Owner 同时是管理员时走 Owner 日常路径。

激活锁定 DRAFT Project 后重验强 ETag、当前 Owner、Owner 身份和 ACTIVE membership、固化模板以及 Content provenance。固化模板的 PUBLISHED 与 RETIRED 都可解释，DRAFT 或缺失版本不可解释。非研发类型的客户名称在创建草稿时可空，但激活时作为字段级 422 校验。

PATCH 事件只记录变更字段名；激活事件和 Security Audit 只记录安全生命周期摘要。客户名称、描述、客户参考、交付地点和联系备注不复制到事件或审计摘要。

## Alternatives considered

- 先加载全部 Project 再按 membership 过滤：拒绝，分页总数和 Workspace 计数会形成侧信道。
- 在 Workspace 保存项目计数：拒绝，会复制权限相关派生事实并带来失效窗口。
- 管理员获得全部 Owner 日常能力：拒绝，治理权限不应隐式代替当前负责人。
- Retired 模板阻断激活：拒绝，退役只禁止新 Project 选择，不应困住已经固化该版本的草稿。

## Consequences

所有新 Project 查询消费者必须复用相同 SQL 可见性口径；能力字段不能被当作授权凭据。模板版本一旦被 Project 固化，就需要长期保持可解释。M2-08 的归档/恢复和后续 Activity 投影必须延续强 ETag、重新鉴权与安全摘要约束。
