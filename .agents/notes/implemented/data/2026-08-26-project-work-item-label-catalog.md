# Agent Note: Project 级 Work Item 状态与优先级标签目录

Status: implemented

## Problem

既有 Work Item 把具体状态和优先级解释为模板或代码内固定枚举，导致 Table、Kanban、Content 工作台无法共同新增、改名、调色或停用标签。项目表格详情抽屉没有可恢复 URL，日期选择器选择任意日期时也没有提交字段命令。若只在前端维护标签，会产生过滤、排序、迁移、统计和并发写入的第二真源。

## Decision

M2-19A 在每个 Project 下建立状态与优先级标签目录。V41 新增目录版本、状态标签和优先级标签表，并把 Work Item 的 `status_code`、可空 `priority` 约束到所属项目目录；存量 Project 按固化模板回填状态，并补入受保护的 `NOT_STARTED` 与四个既有优先级。新建 Project 在原子初始化事务中同时建立标签目录和 Content。`NOT_STARTED` 是所有项目统一的初始状态：可改名、改色、排序，但不可停用或删除；新 Work Item 固定从它创建。既有 Work Item 不改写。

Owner 与 ACTIVE Member 可以新增、改名、选择受控色板、排序、停用、启用和删除标签；非成员 CompanyAdmin 只读。所有目录写入使用强 `If-Match` 和项目事实写锁。已被 Work Item 引用的标签不可删除，并返回“你不能删除正在使用的标签”；停用只阻止未来选择，既有引用、筛选、看板泳道和历史展示继续可读。所有启用状态之间可直接迁移；自定义状态保留独立 `status_code`，并以兼容类别 `TODO` 参与既有开放项与跨项目粗粒度统计。每个具体状态仍可单独筛选和报告。

V42 将状态与优先级共用的受控色板扩展为 Monday 标签编辑器的 33 个可选色。OpenAPI 枚举、应用层校验、数据库检查约束和两个前端标签编辑入口消费同一组令牌；旧的 10 个非重名颜色令牌继续保留读取和展示能力，但不再出现在新编辑色板中，避免既有目录数据因色板升级失效。`INDIGO` 与 `TEAL` 令牌直接采用 Monday 对应色值。

优先级由固定枚举改为项目目录中的字符串代码且允许空；新建工作项默认不设置优先级。排序秩、筛选名称和展示颜色均从目录读取。Table、Kanban 与 Content 工作台消费同一目录和即时版本，不复制标签事实。工作项 wire 契约把优先级代码视为从目录取得并原样回传的不透明字符串，不重复声明格式约束；稳定代码格式只在标签创建和修改边界校验，避免客户端再次固化目录内部规则。OpenAPI 兼容门禁关闭 `incompatible.request.enum.decreased`，使旧枚举变为开放代码集合时继续接受全部旧请求值；M2-24 另批准关闭 `incompatible.response.enum.increased`，并要求生成客户端使用 unknown-default 分支承接治理枚举增长。类型、正则和其他破坏性检查保持启用。

项目总览表格以 `/projects/{projectId}/overview?view=table&workItemId={itemId}` 表示已打开的详情抽屉；刷新、前进/后退和直接访问恢复同一工作项，关闭只移除 `workItemId`，且该参数变化不触发项目列表重载。截止日期选择器绑定 `YYYY-MM-DD` 并在每次 `update:modelValue` 时调用已有强 ETag、幂等字段命令；路由和日期修复不新增数据库事实。

本决策部分替代 [Work Item 核心契约](../architecture/2026-08-22-work-item-core-contract.md) 中“模板迁移图是唯一运行时状态源、优先级秩固定”的描述、[Content 管理契约](../product/2026-08-22-content-management-contract.md) 中“视图状态只来自固定模板”的描述，以及 [Project 创建契约](../product/2026-08-20-project-creation-contract.md) 中仅初始化 Content 的描述；三份记录其余编号、锁、权限、软删除和视图同源决定继续有效。

## Alternatives considered

- 只在前端保存自定义标签：拒绝。服务端过滤、排序、迁移和并发校验会继续使用旧枚举，多个视图无法共享真源。
- 修改模板版本并让既有 Project 跟随升级：拒绝。会改变模板不可变与 Project 固化版本语义，也无法表达每个 Project 独立配置。
- 删除时把既有 Work Item 自动迁移到 `NOT_STARTED`：拒绝。会无提示改写历史业务状态；被引用标签必须先显式迁移完才能删除。
- 停用后隐藏既有标签和泳道：拒绝。会使仍然引用该代码的事项无法查找或迁移。
- 为每个自定义状态新增跨项目统计类别：拒绝。会破坏 `TODO/IN_PROGRESS/DONE/CANCELED` 的兼容汇总；具体状态报告和粗类别统计分别承担两种用途。

## Consequences

备份恢复、项目复制或后续导入必须把标签目录与 Work Item 一起视为一致性集合。新增 Work Item 写入口必须从项目目录验证状态和优先级；客户端不得再把优先级当封闭枚举或从模板推断可选状态。删除和停用需要回归既有引用、筛选、游标排序、Content View Config 与 Kanban 泳道。色板令牌只能以兼容新增方式扩展；移除旧令牌前必须先迁移所有目录数据。若未来需要受控迁移边、标签描述、任意颜色或跨项目共享目录，必须另立决策并迁移现有 Project 目录；当前实现只提供 Monday 33 色受控色板和启用状态间的全互通。
