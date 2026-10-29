# Agent Note: 个人项目范围与公司项目总览

Status: implemented

## Problem

COMPANY_ADMIN 的公司级项目可见范围混入个人工作台、项目侧栏、首页计数和归档页，使未加入的项目与本人负责或参与的项目混在一起。个人工作入口与公司管理职能需要明确分开。

## Decision

工作台、项目侧栏和首页项目计数固定使用 `actorAccesses=OWNER,MEMBER`，工作台的归档页固定使用 `OWNER`。工作台的“我的角色”筛选只提供负责人和成员，未选择角色时仍使用本人范围。

新增 `/admin/company/projects`（路由名 `company-projects`），继承公司管理父级角色要求。页面使用 YpTabs 分为未归档和已归档；未归档支持进行中/已结束筛选以及结束和重新打开操作，状态语义由[项目结束与重新打开](2026-10-27-project-ended-state.md)拥有；查询不传 `actorAccesses`，公司管理员可查看全公司项目。“我的参与”显示负责人、成员或未加入。已归档页复用 `ArchivedProjectTable`，管理员可在这里恢复或计划删除项目。

后端、OpenAPI 与授权保持不变，管理员读取范围仍由服务端 `:admin` 决定；能力字段只控制界面，命令继续由服务端鉴权。查询的权限与分页口径沿用[查询契约](2026-08-20-project-query-and-activation-contract.md)，归档访问、恢复、删除缓冲期与永久清除沿用[归档决定](2026-10-09-project-archive-and-deletion.md)。本记录仅部分替代归档决定中的工作台展示范围与公司管理入口。

## Alternatives considered

- 把归档页整体移入公司管理：非管理员负责人会失去恢复和删除入口。
- 在工作台增加“只看我的 / 全公司”切换：公司管理职能仍混在个人工作台。
- 新增 `scope` 查询参数：需要修改契约，而现有 `actorAccesses` 已能表达所需范围。

## Consequences

管理员需要进入公司管理查看未加入的项目；最近项目缓存中的未加入项目在工作台“最近”标签中被过滤。个人工作台和公司总览复用归档表格的查询、恢复、计划删除和撤销逻辑，区别仅为传入的调用人角色范围。
