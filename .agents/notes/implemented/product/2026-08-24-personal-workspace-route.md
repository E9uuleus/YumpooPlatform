# Agent Note: 个人工作台规范路由

Status: implemented

## Problem

项目目录原先直接占用 `/projects`，一级导航又把“工作台”和“项目”拆成两个模块，无法从地址看出目录属于当前登录人员。直接把企业微信 UserID、显示名或内部 UUID 拼入地址会分别带来外部身份变化、重名与不可读等问题；把这个个人入口建成 `catalog.workspace` 又会与每个 Company 唯一的 MAIN 项目归属混淆，并诱导维护者将展示地址误用为授权边界。

规范地址同时跨越身份数据、认证 wire、前端路由和项目导航，且旧目录地址的兼容选择会长期影响收藏、登录返回路径和排障，因此需要冻结稳定身份及与内部 Workspace 的边界。

## Decision

每个 `identity_user` 在 Company 内拥有必填且不可变的 `workspace_slug`。值为 1–64 位小写 URL 安全片段，首尾只能是字母或数字，中间允许 `[a-z0-9._@-]`，并保留 `me`、`new`、`admin`、`settings`。V33 先按 Company、用户创建时间和 UUID 稳定排序，优先规范化既有 WECOM `external_user_id`；冲突者追加用户 UUID 的八位短后缀，无有效候选时使用 `u-{无连字符UUID}`。Company 内建立唯一约束，数据库触发器为遗漏字段的内部插入生成 UUID 回退值，并拒绝修改已写入的别名。

新通讯录成员在创建用户的同一事务中按 Company 获取 advisory lock，再依次尝试可读候选、短后缀和 UUID 回退值。后续显示名、企业微信资料或外部 UserID 变化均不修改别名，也不提供编辑界面。`GET /auth/me` 的 `CurrentAuthenticationUser` 必填返回 `workspaceSlug`；系统不提供按别名查询其他用户的 REST 接口。

项目目录的唯一规范前端地址是 `/workspace/{workspaceSlug}`。登录后的 `/` 与 `/workspace` 使用 `replace` 导航到当前会话别名；地址包含错误或其他人员别名时，不解析或查询目标身份，同样直接替换为当前用户地址。旧 `/projects` 目录不保留重定向并进入 404，以关闭第二个目录地址；既有 `/projects/{projectId}/overview` 等项目资源深链保持不变。

个人工作台只是当前会话项目目录的 URL 命名空间和壳层展示身份，不创建个人 `catalog.workspace`，不参与后端授权，也不改变 Project 的内部 MAIN `workspace_id`。Company 单例 MAIN 的数据归属、生命周期和权限边界继续由 [MAIN 单工作空间契约](2026-08-23-main-workspace-contract.md)拥有；项目可见范围仍只由现有 Company、membership、Owner 和 CompanyAdmin 谓词决定。

## Alternatives considered

- 直接使用内部用户 UUID：稳定且无需新字段，但地址不可读，也无法表达面向人员的工作台身份。
- 每次从企业微信 UserID 动态生成地址：初始可读，但外部身份变更会破坏收藏与登录返回路径，并把外部目录标识变成长期路由事实。
- 使用显示名或可编辑用户名：更友好，但重名、改名、本地化和抢占处理会持续改变规范地址。
- 为每个用户创建一条 `catalog.workspace`：拒绝。Project 仍只能归属 Company 的 MAIN，这些个人行没有业务事实，还会重新引入多 Workspace 与授权歧义。
- 保留 `/projects` 并永久重定向：兼容旧收藏，但会继续承诺旧目录命名并掩盖失效链接；项目详情本就继续使用该前缀，目录 404 能明确区分两类语义。
- 接受任意人员别名并通过后端查询目标用户：当前产品没有浏览他人个人目录的能力，会增加身份枚举面和新的授权语义，因此只规范化到当前会话。

## Consequences

数据库、认证响应、OpenAPI 和生成客户端新增必填字段；所有内部用户插入即使尚未显式传值也会获得合法 UUID 回退别名。别名进入浏览器历史、服务日志和可能的引用来源，因此它只允许使用已确认的企业微信标识规范化结果，不承载权限或秘密，且不开放反向身份查询。

首页路由和一级“项目”导航不再发布，工作台在目录与项目详情中持续高亮。旧 `/projects` 目录收藏明确失效为 404，而项目详情收藏保持兼容。未来若要允许改名、访问他人工作台或让个人目录拥有独立项目归属，必须新增决策，定义别名历史、重定向期限、枚举防护、授权与 MAIN 数据迁移，不能直接放宽当前守卫或修改不可变字段。
