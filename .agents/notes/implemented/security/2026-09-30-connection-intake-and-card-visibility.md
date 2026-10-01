# Agent Note: 连接投递权限与跨项目最小卡片授权

Status: implemented

## Problem

来源项目需要向另一个项目投递工作项，而操作人未必是目标项目成员。若复用普通工作项写入权限会阻断投递；若把投递权限扩成目标项目访问或任意写入，又会暴露正文、成员和其他工作项。连接必须给出独立、明确且可在查询与事务中复核的最小授权。

## Decision

新建并关联要求来源项目 OWNER/MEMBER、两端项目 ACTIVE、目标位于连接列目标集合，不要求目标项目成员。新建项固定为目标项目根项，状态 `NOT_STARTED`，报告人为操作人；优先级、处理人、描述、日期为空。只能选择目标项目启用类别，省略时使用第一个启用类别。工作项与连接由同一创建内核、事务和 `createConnectedWorkItem` 幂等作用域提交，任何失败都回滚。

关联已有和候选搜索要求两端 OWNER/MEMBER；解除连接只需任一端 OWNER/MEMBER，同时要求两端项目 ACTIVE、列未删除。列创建和修改要求来源项目成员，删除要求 OWNER。企业管理员的公司级可见性不授予写权限。不可见资源返回 404，可见但无写权限返回 403。所有写命令在事务内取得有序项目锁后重新复核权限与生命周期，预检不构成最终授权。

有效用户可以搜索本公司 ACTIVE 项目的 `id/code/name`。catalog 的 `ProjectConnectionTargetQuery` 只负责这种最小项目信息及目标生命周期锁；一般项目可见性仍由 actor-scoped `ProjectAccessSnapshotQuery` 决定。workitem 不读取 catalog 的项目或成员表。新建选项只返回目标项目名称、启用类别的 `id/name/colorToken` 和默认类别 ID，不返回目标项目成员信息。

有效连接本身授予两端最小卡片读取：用户能看见任一端项目，就可读取两端卡片。`ConnectionCard` 的字段固定为 `workItemId, itemNo, title, archived, projectId, projectCode, projectName, projectLifecycle, status, priority, category, assignee, canOpen`。状态仅含 code/name/colorToken/category，优先级仅含 code/name/colorToken，类别仅含 id/name/colorToken，处理人仅含 userId/displayName。`canOpen` 只表示当前用户可见卡片所在项目；卡片不授予打开详情或修改目标的权限，也不包含描述、评论、附件、计时、截止日期、成员列表或其他正文。

读取先用公司与项目限定 SQL。A6 静默忽略外项目工作项 ID，以免批量请求成为探针；出站只返回有有效连接的列，入站每项最多 50 条并返回完整计数。A7 在分页和计数前依据 catalog 返回的可见项目集合裁剪连接，不先取全量后在 Java 中过滤。A6 批量读取端点卡片、项目快照、访问快照及人员显示名，1 行与 100 行请求均为 8 次 SQL。任何端点已删除时读取省略连接，恢复后重新出现；项目归档保留读取。

连接 Activity 的目标侧只包含目标项目和目标工作项引用，安全参数为空，不包含来源项目、来源工作项或列标识。来源侧只用事件中列名生成文案，普通关系的隐藏占位继续按[关系可见性决策](2026-08-31-cross-project-work-item-relation-visibility.md)执行。聚合、锁序和事件归属见[连接架构决策](../architecture/2026-09-30-work-item-connections.md)。

## Alternatives considered

- 要求新建投递人也是目标成员：拒绝。会取消已确认的跨项目非成员投递用途；新增写权限仅限列授权的新建根项与连接。
- 仅因看见目标项目就允许关联已有：拒绝。企业管理员只读能力不能代替两端成员资格，已有工作项关联仍须双侧成员。
- 给非成员返回空卡片或普通关系隐藏占位：拒绝。连接明确授权最小卡片，但该授权不延伸为正文或项目访问。
- 返回完整 WorkItemDetail，再由前端隐藏敏感字段：拒绝。传输本身已泄露越权数据；使用独立闭合 schema 和后端最小查询。
- 先分页再过滤不可见连接：拒绝。总数、页面稀疏度与跨项目标识会泄露不可见协作事实；过滤必须在 SQL 中先于计数与分页。
- 让 Activity 目标侧记录来源标识以便跳转：拒绝。目标项目动态的读者未必拥有来源访问权，连接卡片之外不增加隐式来源披露。

## Consequences

本记录新增连接授权边界，没有取代或放宽普通关系规则，因此保留两条记录并互链。前端必须以 `canOpen` 和 `canUnlink` 呈现能力，所有实际读取与写入仍由服务端授权；缓存、搜索、导出和后续通知也不得扩大卡片字段集合。

`WorkItemConnectionIT` 覆盖非目标成员投递、双侧关联、任一端解除、只读管理员、固定卡片字段、删除恢复、SQL 裁剪及 100 行固定查询次数；`ConnectionHttpIT` 通过真实会话验证 CSRF、404/403、强 ETag、未知字段拒绝和幂等重放；`ConnectionConcurrencyIT` 覆盖等待期间撤销成员资格，`ConnectionEventProjectionIT` 校验目标投影无来源标识。`ModuleArchitectureTest` 同时检查模块边界及 workitem 源码无 catalog 项目/成员表访问。
