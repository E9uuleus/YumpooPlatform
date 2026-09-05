# YumpooPlatform

## Content 工作项类别重构

V48 起，`Content` 仅表示项目级“工作项类别”目录，不再承载工作项类型、表格/看板视图配置或独立工作区。项目工作项总表是唯一表格/看板入口；根项、子项、创建表单和详情抽屉均可通过普通字段切换类别，类别变化不改变编号、父子关系、讨论、附件、项目顺序或 Project+Status Kanban rank。

目录保留 `GET/POST /api/v1/projects/{projectId}/contents`，并提供嵌套 `PATCH/DELETE /api/v1/projects/{projectId}/contents/{contentId}`；工作项类别通过 `PATCH /api/v1/work-items/{workItemId}/content` 修改。旧 Content 页面、`/api/v1/contents/{contentId}`、archive/restore 和 Content 工作项工作区均已删除。默认“需求 / 任务 / 缺陷”受保护但可改名、改色、排序和停用；新类别由 Project Owner 管理，使用过后只能停用。

历史 M2 小节记录当时的交付切片；其中 Content 工作区、Content View Config、Content 级 rank lane 及 `WorkItemType` 描述已由本节取代，当前实现以 OpenAPI、V48 与现行 Agent Note 为准。

## M2-24 项目协作阶段收口

M2-24 对 M2 已存在的 Project、Content、Work Item、关系、Activity 与 Product 切片执行综合回归，并交付 Product 治理 Web 闭环。Product 普通归档现在以 `ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS` 阻断有效 DEVELOPMENT/SUPPORT 关系所指向的不同 ACTIVE Project；CompanyAdmin 可填写 10–500 字理由执行显式覆盖，安全记录前后快照和聚合计数，但不修改 Project 或关系事实。Product 归档事件 v1 兼容增加可选 `mode/blockers`，治理理由不进入领域事件。

M3A/M3B 只能通过 `ProjectFactWriteGuard`、`ProductFactWriteGuard`、`ProductProjectRelationQuery` 与 actor-scoped `WorkItemReferenceQuery` 接入现有事实。跨聚合写入固定使用“Project → 按 UUID 排序的 Product”锁序；Product 归档不反向锁 Project。Web 新增 `/products` 与 `/products/:productId`，提供列表、创建、能力驱动编辑、普通/覆盖归档和恢复，并保持强 ETag、独立幂等键和 412 草稿保留。

本次不制造尚未实现的 provider：Project 归档的 Worklog blocker 在 M3A-13 最终验收，Project/Product 归档及关系解绑的 Feedback blocker 在 M3B-11 最终验收，Project 三类 blocker 总门禁在两者均完成后执行。MAIN Workspace 管理 UI 属于 M5-07，Product Owner 重指派 UI 属于 M5-09（现有 API 保留），提醒调度属于 M4。

```powershell
pnpm verify:m2-24
```

## M2-23 Work Item 领域事件契约冻结

M2-23 冻结 Work Item 八类、Update 三类与 Relation 三类共 14 个核心 v1 事件。冻结清单统一登记类型、版本、聚合、Schema、生产者、Activity 消费者以及接收者引用；同一 v1 只允许新增可选字段，事件名、聚合语义、必填字段、既有字段约束和封闭对象规则不可改变。PR 门禁从目标分支提交临时提取历史 Schema 与合法样例，不提交重复基线；破坏性演进必须新增 v2 并保留 v1。

指派事件只把 `assigneeUserId` 作为候选直接接收者，讨论发布事件只把 `mentionedUserIds` 作为候选 Mention 接收者；消费者仍需按当前账号、Project 权限和资源状态重新鉴权。标题、正文与治理理由不直接成为安全通知载荷。跨项目 Relation 按两侧 Project 分别投影，任一侧不保存或展示另一侧 Work Item ID。Content 与 Attachment 事件继续执行既有契约和 Activity 回归，但不重复进入本次冻结清单。

```powershell
pnpm verify:m2-23
```

## M2-22 跨项目普通关系与不可见端占位

M2-22 在 V43 上让 RELATED、BLOCKS、SOURCE、DUPLICATE 支持跨项目，PARENT_CHILD 继续永久限定同项目。创建和解除要求操作者在两侧均为 ACTIVE OWNER/MEMBER，且两侧 Project 为 DRAFT 或 ACTIVE；仅凭 CompanyAdmin 可见时只读，任一侧归档以 `PROJECT_ARCHIVED` 拒绝写入。事务按 Project UUID、Work Item UUID、Relation 固定锁序，封闭相反方向并发创建。

关系查询先由 catalog 的 actor-scoped 批量快照判断对端 Project 可见性，再进行分页和计数。失去对端权限后不返回关系行、数量、类型、relationId、ETag 或对端字段，只提供与类型过滤无关的单一 `hasHiddenRelations` 信号。Web 可远程选择可写目标 Project；父子关系锁定当前项目，同项目继续切换抽屉，跨项目则进入目标项目总览并通过 `workItemId` 打开详情。

```powershell
pnpm verify:m2-22
```

## M2-20 Activity 追加投影与游标查询

M2-20 以 V44 建立 Project、Product、Feedback 三类预留范围的 append-only Activity 投影。当前公开 Project 动态页和 Work Item 详情“动态”页签；Product 生命周期会进入内部投影，但暂不开放 Product Activity 页面或 HTTP 查询。投影只接收 V44 切点后的新事件，不回填旧 Outbox，API 通过 `historyStartedAt` 明示历史起点。

投影保存模板码、服务端中文摘要和严格白名单参数，并在写入时固化行为人显示名；正文、客户字段、删除或治理理由、哈希和跨范围标识不会落库。项目与事项查询每次按当前 Project 可见性重新鉴权，并使用绑定范围与筛选指纹的 `(occurredAt, id)` 倒序游标。

```powershell
pnpm verify:m2-20
```

## M2-21 同项目 Work Item 普通关系

M2-21 在 V43 上开放 PARENT_CHILD、RELATED、BLOCKS、SOURCE、DUPLICATE 五类同项目关系，提供按当前侧语义的分页查询、全事项候选搜索、挂接既有项、带强 ETag 与幂等键的原子换父和带理由解除。父子永久限制为“根项 + 一层子项”；子项不能成为父项，有子项的根项不能成为子项。重复逻辑关系返回既有事实，不重复写事件。

Work Item 详情抽屉新增共享的懒加载“关系”页签，项目页与 Content 页使用同一组件；不合法候选显示稳定原因，换父必须确认，已删除对端保留占位且可解除。创建、解除、换父分别进入隐私安全的关系事件和双方 Activity；跨项目及失权端脱敏留给 M2-22。

```powershell
pnpm verify:m2-21
```

## M2-21A 项目工作项直接子项（历史切片）

M2-21A 以 V43 `work_item_relation` 提前交付 M2-21 的直接父子项切片：根项下可原子创建全新子项、按父项查询和在同父兄弟间排序；项目与 Content 的 Table、Kanban、筛选计数只展示根项，列表批量返回 `subitemCount`。删除不会级联关系或晋升子项，恢复后回到原嵌套。该切片作为历史交付保留，剩余能力已由 M2-21 完成。

项目表格提供 Monday 形态的单层展开子表，首次展开懒加载并缓存；子表共享列偏好和字段编辑、详情/讨论、选择、排序、拖拽与快速新增能力，但保持独立状态且不显示递归入口。创建默认继承父 Content，也可选择同 Project 的其他 ACTIVE Content。

```powershell
pnpm verify:m2-21a
```

## M2-19A 项目表格路由、截止日期与标签目录

M2-19A 为项目总览 Table 的工作项详情抽屉增加 `?view=table&workItemId={itemId}` 深链接，支持直接访问、刷新和前进/后退；日期选择器选择任意具体日期、Today 或清空都会调用既有强 ETag、幂等截止日期命令。

V41 增加 Project 级状态与优先级标签目录。Owner 与 ACTIVE Member 可新增、改名、选择受控颜色、排序、启停和删除未引用标签；CompanyAdmin 只读。受保护的 `NOT_STARTED/未开始` 是未来工作项统一初始状态，不可停用或删除；停用标签继续展示已有引用。Table、Kanban、Content、筛选与排序消费同一目录，优先级允许空且新建默认空。路由和日期修复本身不涉及数据库结构，标签配置涉及 V41 三张新表和 Work Item 项目内外键。

```powershell
pnpm verify:m2-19a
```

## M2-18 附件上传与安全扫描闭环

M2-18 为 Work Item 与已发布、未删除的讨论 Update 提供附件意图、64 KiB 流式 PUT、Company/Project 配额预约、持久扫描队列、元数据分页和 APP_MANAGER 受控重扫。单文件固定上限 100 MiB；扫描器不可用会按 5 秒、30 秒重试，第三次失败保留封存内容 24 小时供带理由、幂等键与强 ETag 的重扫。最终化会重新验证原上传者和父对象当前可写性，并在同一事务提交附件、配额、Security Audit 与隐私安全的 AVAILABLE Outbox 事件。

Web 在 Work Item 详情直接显示附件，讨论附件只有展开对应 Update 后才加载。上传完成后按 1/2/5 秒退避轮询最多 5 分钟；PUT 结果未知时先读取 metadata，可继续上传便复用原 attachmentId，否则继续轮询。Feedback 两种 owner 仅冻结枚举并留给 M3B。

```powershell
pnpm verify:m2-18
```

## M2-19 附件下载、逻辑删除与安全维护

M2-19 为 `AVAILABLE` 附件增加实时父对象授权后的 64 KiB 流式下载。服务端在打开响应流前核对正式文件存在性、大小和 SHA-256，并返回检测 MIME、UTF-8 附件文件名、`nosniff`、`private, no-store` 与 `CSP: sandbox`；不支持 Range，也不暴露永久 URL、摘要或存储路径。Web 使用同源原生链接接收附件，避免 SDK 把 100 MiB 文件缓冲进 JavaScript 内存。

当前父对象可写成员可用强 ETag、XSRF、稳定幂等键和 1–500 字理由执行逻辑删除。事务同时写入墓碑、释放逻辑配额、Security Audit 与 `filestorage.attachment_deleted` v1 Outbox；普通 metadata、列表和下载立即隐藏墓碑，但物理内容仍受 `DELETED` 引用保护。

V38 增加物理 Blob 注册表、发布/清理互斥租约、可续跑维护运行和内部对账问题。维护任务默认启动延迟 5 分钟、每分钟处理最多 100 条、完整运行间隔 24 小时：过期上传意图被拒绝并释放预约配额，临时文件和无活动引用的正式孤儿需连续观察满 24 小时。物理删除默认关闭；开启时必须同时配置非空批准引用。缺件、大小/摘要不符、孤儿、异常目录项、配额偏差和陈旧扫描任务仅输出 runId、问题代码与计数。

```powershell
pnpm verify:m2-19
```

附件恢复、Range/在线预览，以及受逻辑删除引用保护的正式 Blob 的 30 天、legal hold 与备份门禁清理由 M5-17 负责。

完整普通门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。Defender、NTFS 与 EICAR 真实门禁必须显式 opt-in：`pnpm verify:m2-18:live`。

## M2-17 Update 编辑、删除与治理删除

M2-17 在独立 `WorkItemUpdate` 讨论流上增加单条 GET、PATCH 与 DELETE。作者可在服务端固定 15 分钟窗口内编辑或无理由自删，截止时刻本身已超窗；当前 ProjectOwner 可提供 1–500 字理由随时治理删除，包括 Project 或 Content 已归档时。所有写操作使用强 `If-Match`，只推进 Update 自身版本，不改变父 Work Item。

编辑重新净化富文本并原子替换 ACTIVE 项目成员 Mention；净化后无变化不会增版或发事件。删除不可恢复，正文置空但保留原时间线位置、作者、编辑事实、删除时间、操作者、治理理由和 Mention 快照。成功删除、Security Audit 与 Outbox 同事务提交，失败治理使用独立失败审计；编辑/删除事件不携带正文或可还原摘要。Activity 投影与查询继续由 M2-20 统一实现。

Web 根据服务端能力显示编辑、自删和治理删除，编辑器与发布采用同一受限 Tiptap 配置且草稿互不影响。作者操作在本地到达截止时主动隐藏，但最终仍由服务端裁决；412 会单条刷新并保留编辑草稿，409 关闭写能力并保留草稿供复制。墓碑只显示固定占位，治理删除额外显示理由，绝不渲染原正文。

```powershell
pnpm verify:m2-17
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-16 Work Item 独立讨论与项目成员提及

M2-16 新增独立 `WorkItemUpdate` 讨论流及 `GET/POST /api/v1/work-items/{workItemId}/updates`。首次读取最新窗口，对外始终按旧到新展示，并以 Base64URL 复合游标向上加载更早内容；发布要求 XSRF 与幂等键，同键同请求精确重放。讨论不会推进父 Work Item 的版本、ETag 或更新时间。

服务端用固定富文本白名单净化 HTML，只接受绝对安全链接和合法 Mention wire，并用发布时 ACTIVE 项目成员及权威显示名重写提及。Owner 与 ACTIVE Member 可发布，CompanyAdmin 以及归档 Project/Content 只读；非成员、跨企业和墓碑事项隐藏为 404。发布事件只传播引用、作者、排序后的 Mention ID 与版本，不传播正文。

Web 详情抽屉提供“详情 / 讨论”双页签，首次进入讨论时才加载，支持加载更早、手动刷新、受限富文本和键盘 Mention。页面只渲染服务器净化响应；失败保留草稿和幂等键，关闭或切换事项会确认未发布草稿。编辑/删除、附件、Activity、实时更新、通知和未读数继续延期。

```powershell
pnpm verify:m2-16
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-15 Work Item 软删除、恢复与归档只读

M2-15 新增 `DELETE /api/v1/work-items/{workItemId}` 与 `POST /api/v1/work-items/{workItemId}/restore`。Project Owner 和 ACTIVE Member 都可写，CompanyAdmin 保持只读，非成员隐藏为 404；父 Project 或 Content 归档后，创建、PATCH、迁移、rank、删除和恢复统一拒绝。普通详情、Table、Kanban、参与人排序与开放事项 blocker 均排除墓碑，删除/恢复命令使用强 ETag、XSRF 和持久化幂等键。

V34 通过生成列 `active_lane_rank` 与可延迟唯一约束保证活动项 rank 唯一，同时允许墓碑保留重复历史 rank。恢复保留编号、字段与原状态；历史 rank 空闲时复用，被占用时在 lane 锁内恢复到顶部。删除/恢复各发布不含正文和内部 rank 的 v1 事件，备份恢复覆盖删除时间、操作者与理由。

Web 详情抽屉提供危险操作确认、必填理由和未保存草稿警告；删除成功后只在当前页面内存保留多条即时撤销提示，不新增持久回收站。传输失败重试复用原幂等键，409/412 只刷新真源、不自动重提。物理清理、Activity 投影以及 Relation/Worklog/Feedback 引用展示继续由后续里程碑交付。

```powershell
pnpm verify:m2-15
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-14 现状修正：项目管理与 MAIN 主工作空间

每个 Company 现在由数据库保证恰好一个 `code=MAIN / status=ACTIVE / sortOrder=0` 的内部主工作空间。V32 按既有 MAIN、首个 ACTIVE、首个 ARCHIVED的优先级保留稳定身份，把所有生命周期 Project 迁入 MAIN 后删除其余 Workspace；迁移不可逆，升级前必须备份，回滚使用备份恢复。Project 创建请求不再接受 `workspaceId`，服务端在原子事务内锁定 MAIN 自动归属；Workspace 公开写能力只保留名称和描述 PATCH。

`/projects` 已重构为单表管理页，默认显式查询全部生命周期。服务端支持项目名称/编码搜索、项目类型/负责人/我的角色多选、公司时区自然日修改时间筛选以及仅来自可见项目的负责人候选；行与 total 复用相同权限和筛选谓词。页面提供折叠搜索、四列即时筛选、精确列序、公司时区分钟显示、移动端连续行和无 Workspace 的创建抽屉；侧栏统一为“项目 → 管理项目”层级。

## M2-14 Kanban rank 与受控拖动

M2-14 以 V31 为 Work Item 增加状态泳道内持久化 rank。升级按既有 `item_sequence DESC, id ASC` 回填 39 位定长十进制位置；创建与普通状态迁移置于目标状态顶部，同状态移动支持 `START/BEFORE/AFTER/END`，间隙耗尽时在 Content/状态 lane 锁内保持相对顺序重平衡。Kanban 查询要求恰好一个状态、拒绝 Table sort，并固定按 `rank ASC, id ASC` 稳定分页；移动命令要求 XSRF、强 `If-Match` 与幂等键。

Web 保留 Content 的多状态分组，并在每组内渲染独立状态泳道。鼠标和触控只从 Pointer 拖动手柄启动，提供阈值、取消、合法投放与边缘滚动；键盘/触控菜单覆盖上下移、顶底定位和合法跨状态。要求说明的跨状态移动先确认；提交期间显示 pending，失败恢复快照，传输失败的明确重试复用原幂等键，409/412 只刷新服务端事实与能力。M2-15 删除恢复、M2-20 Activity、M2-23 事件冻结与 M2-24 阶段收口均已交付。

```powershell
pnpm verify:m2-14
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-13 Table 高级查询与共享视图

M2-13 扩展 `GET /api/v1/contents/{contentId}/work-items`，支持标题关键字、模板状态、优先级、处理人、截止区间、严格更新时间下界和最多三层白名单排序。服务端先校验 Project/Content 可见性，再校验查询；同字段 OR、不同字段 AND，计数与分页复用同一谓词并追加 `id ASC`。状态按模板顺序、优先级按领域顺序、人员按当前显示名排序，未知历史人员和空值置后；旧客户端未传条件时仍按事项序号倒序。V30 为更新时间、处理人和截止日查询增加部分索引。

Web 的 Content 配置页与工作项工作区复用同一高级查询编辑器。无 `custom=1` 时从 Content 共享默认初始化；临时修改完整同步 URL，搜索 300ms 防抖并替换历史，其他筛选/排序形成浏览历史。Table 显式提交完整查询；Kanban 复用非排序筛选并与共享状态组取交集。Owner 可保存共享默认，Member 与 CompanyAdmin 仅临时查询；412 保留临时条件，并只把 filters/sort 合并进最新 Content 后供用户明确重提。

```powershell
pnpm verify:m2-13
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-12 Work Item 独立状态迁移

M2-12 新增 `POST /api/v1/work-items/{workItemId}/transitions`，由服务端按 Project 固化模板版本校验精确迁移边并计算 `capabilities.availableTransitions`。命令必须携带 XSRF、强 `If-Match` 和幂等键；状态/类别、新版本、一条 `workitem.work_item_status_changed` v1 Outbox 和幂等结果原子提交。迁移不改变 rank 或任何协作字段；CompanyAdmin 保持只读，归档、终态、非法边与并发版本冲突均按固定问题语义拒绝。Flyway 仍停在 V29。

Web 仅在 Work Item 详情抽屉展示服务端返回的合法目标。说明最长 500 字，迁移边可要求必填；传输失败的显式重试复用原幂等键。成功后刷新详情与当前 Table/Kanban，保留未保存字段草稿；412 沿用冲突面板且不自动重试。M2-13 高级查询、M2-14 rank/拖动、M2-15 删除恢复、M2-20 Activity、M2-23 事件冻结与 M2-24 阶段收口均已交付。

```powershell
pnpm verify:m2-12
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-11 Work Item 协作字段与乐观锁

M2-11 在既有 Work Item 真源上开放标题、优先级、处理人、描述、备注、计划起止日和截止日的完整快照更新。GET、POST 与 PATCH 返回强 ETag；PATCH 要求 XSRF 与 `If-Match`，无变化不增版、不改审计时间、不发事件，陈旧版本固定返回 412 且不覆盖先写结果。处理人只允许选择同 Project 的 ACTIVE membership，自然日以 `YYYY-MM-DD` 存取；Flyway 继续停在 V29。

Web 创建与详情表单复用 ACTIVE Project 成员分页，Table 呈现全部固定协作列，保存后刷新当前 Table/Kanban 真源。发生 412 时保留本地草稿并读取服务器最新版，只允许用户选择载入最新版或基于最新 ETag 明确重提；CompanyAdmin 与归档资源继续只读。状态迁移、高级查询与 rank/拖动已由 M2-12 至 M2-14 交付；删除恢复和 Activity 投影分别留给 M2-15 与 M2-20。

```powershell
pnpm verify:m2-11
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-07 Product–Project 关系

M2-07 已交付 V27 关系小聚合、四类关系、单一可选主 Product、关系强 ETag、持久化幂等、软移除与重新关联新 ID。关系写入锁定 Project 但不增加 Project 版本；Owner 可写，成员和非成员 CompanyAdmin 只读。Product 读取范围现包含关联 Project 的 ACTIVE member，Product 写权限仍显式限制为 ProductOwner 或 CompanyAdmin；项目目录支持远程 Product 筛选。

M2-08 已交付 Project 普通归档、治理覆盖归档和恢复；历史 Workspace 迁移事件仍可读取，但 MAIN 单工作空间实施后不再提供或产生跨 Workspace 迁移。M2-10 已接入 Work Item 的真实 `OPEN_WORK_ITEMS` provider；Worklog 与 Feedback provider 分别延期到 M3A-13 与 M3B-11，完整 PPM-014 总门禁在两者均完成后执行，不制造零值 blocker 或虚假 Verified 结论。

OpenAPI、生成 TypeScript SDK、三类 v1 事件、Vue 关联产品页、PostgreSQL 并发/回滚测试和备份恢复事实已同步。M2-24 已冻结 Project→Product 锁序和活动研发/支持项目 Product blocker；真实 Feedback 引用的解绑 blocker 由 M3B-11 建立，不在尚无 Feedback 真源时伪造已验证结论。

```powershell
pnpm verify:m2-07
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-06 Project 范围查询、激活与工作台

M2-06 已交付 Project 权限过滤目录、完整详情、Owner 配置 PATCH 与 DRAFT 激活，并将 Workspace 的 `visibleProjectCount` 接入调用人可见的 DRAFT+ACTIVE Project 分组计数。激活在同一事务中重验 Owner、固化模板版本与 ACTIVE Content provenance；PUBLISHED 和 RETIRED 模板均可解释既有草稿，非研发类型在激活前必须补齐客户名称。

OpenAPI、生成的 TypeScript `ProjectsApi`、Project update/activation v1 事件、Vue 项目工作台与备份恢复覆盖已同步。Project 归档、Product–Project 关系、Content CRUD/View Config 和 Activity 投影分别留给 M2-08、M2-07、M2-09 与 M2-20。

```powershell
pnpm verify:m2-06
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-05 Project 成员与唯一负责人治理

M2-05 交付 Project 成员分页与候选搜索、加入/移除/重激活、唯一负责人原子重指派、Security Audit、三类 v1 Outbox 与 PROJECT OWNER_MISSING 投影。成员写固定按 Project → membership 加锁，负责人重指派先确保新负责人 ACTIVE membership，旧负责人保留为普通成员；V21 延迟约束继续保证提交时唯一 owner 有效。

OpenAPI、生成 TypeScript `ProjectsApi`、V24/V25 迁移、备份恢复与 `pnpm verify:m2-05` 已同步。本步不实现 Vue 页面、Project 列表/详情/PATCH/激活、归档治理、Activity 或 Worklog 审批人迁移。

## M2-04 Project 原子创建与初始 Content

M2-04 交付 `POST /api/v1/projects`：COMPANY_ADMIN 显式选择已发布模板版本，在单一事务内创建 DRAFT Project、ACTIVE owner membership、模板定义的三类初始 Content、Security Audit、两类 Outbox 和可字节级重放的幂等响应。当前请求不再提交 Workspace，服务端自动归属 Company 的 MAIN；Project 类型和模板引用创建后固化，非研发客户名的必填检查保留到 M2-06 激活。

OpenAPI、生成的 TypeScript `ProjectsApi`、两类 v1 事件与备份恢复覆盖已同步。列表/详情/PATCH、激活、成员管理、Content API/View Config 和 Activity 投影分别留给 M2-05、M2-06、M2-09 与 M2-20。

```powershell
pnpm verify:m2-04
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-03 Product 生命周期与负责人治理

M2-03 在 `catalog` 中交付 V18 Product 主数据、SQL 权限分页、详情与资料更新，在 `administration` 中交付归档、恢复和唯一负责人重指派。负责人只保存在 `product.owner_user_id`；离职或禁用通过 `OWNER_MISSING` 治理投影逐 Product 打开问题，不改变生命周期、不自动提升其他成员。

OpenAPI、生成的 TypeScript `ProductsApi` 和五类 v1 事件已同步冻结。M2-07 已提供 ProductProjectLink 真源与项目成员可见性；M2-24 已接入活动 DEVELOPMENT/SUPPORT Project blocker、治理覆盖与 Product Web。Feedback blocker 继续由 M3B-11 的真实 provider 接入，不制造空 blocker。

```powershell
pnpm verify:m2-03
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-02 Workspace 基线与 MAIN 收口

M2-02 的 V17 建立了 Workspace 历史基线；V32 已将其收口为每 Company 唯一且永远 ACTIVE 的 MAIN。列表和详情继续提供稳定 ID，COMPANY_ADMIN 可带强 ETag 修改名称和描述；创建、排序、归档、恢复和项目迁移接口已退役。Workspace 不保存成员、角色或授权事实。

OpenAPI 与生成 TypeScript 客户端已同步当前只读/改名能力；已冻结的 v1 Workspace 写操作、迁移操作和相关字段以 deprecated 拒绝适配保留，历史 Workspace 领域事件 schema 继续用于读取既有事实。Project 创建在事务内读取 MAIN，不再接受客户端 Workspace 选择，旧 `workspaceId` 仅作为忽略的兼容字段接收。

```powershell
pnpm verify:m2-02
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M2-01 固定模板目录与版本治理

M2-01 交付 `RND_V1`、`PRE_SALES_V1`、`IMPLEMENTATION_V1`、`HYPERCARE_V1` 四个已发布模板版本，以及每套固定的需求、任务、缺陷 Content blueprint 和完整状态迁移目录。模板以 `templateKey + version` 标识并使用稳定 UUID 聚合事件；PUBLISHED/RETIRED 结构由 PostgreSQL 触发器保护，只允许 `DRAFT → PUBLISHED → RETIRED`。

登录成员可读取已发布可选版本，`COMPANY_ADMIN` 可带强 ETag、UUID 幂等键及理由发布/停用版本。治理事务原子写入模板状态、幂等结果、Security Audit 和 Outbox，并同步冻结 OpenAPI、生成 TypeScript 客户端及两类 v1 事件契约。本步不创建 Project/Content 实例；PPM-006 的 Project、owner membership 和初始 Content 原子初始化仍由 M2-04 交付。

```powershell
pnpm verify:m2-01
```

完整门禁需要 Java 21、Node 24.14、pnpm 11.16 和可运行 PostgreSQL 17 Testcontainers 的 Docker Linux engine。

## M1-15 生产环境首次身份引导

M1-15 为尚未初始化平台角色的生产环境提供一次性、停服终端引导：使用同一生产 JAR 完成真实企微通讯录全量同步，并把两个不同的在职、启用成员原子授予首个 `APP_MANAGER` 与首个 `COMPANY_ADMIN`。入口默认关闭，不新增本地账号、HTTP/OpenAPI 接口或数据库迁移；成功后治理闩锁永久关闭，失败则保留输入文件供修正重试。

```powershell
pnpm verify:m1-15
pnpm verify:m1-15:deployment
```

Windows x64 服务器产物位于忽略目录 `out/m1-15`；Electron 不重新打包，继续使用 M1-14 `0.1.0` PILOT。当前部署入口见 `deployment/windows/RUNBOOK.md`，历史 M1-14 手册保留为 `RUNBOOK-M1-14.md`。真实 UserID、Secret、Cookie 和扫码参数不得进入仓库、日志、审计正文或验证报告，目标环境证据保持 `ENV_PENDING`。

## M1-14 PC Chrome / Electron 企业微信扫码登录

PC Chrome 现在由 `/login` 的显式按钮进入企微官方 `qrConnect`；Electron 0.1.0 使用系统默认浏览器、PKCE S256、60 秒一次性 handoff 和 Windows safeStorage 建立正式 `ELECTRON` 会话。当前步骤只生成服务器与 Windows x64 桌面 PILOT 包，不自动更新开发服务器，真实扫码证据保持 `ENV_PENDING`。

```powershell
pnpm verify:m1-14
pnpm verify:m1-14:deployment
```

产物位于忽略目录 `out/m1-14`，包含服务器/桌面 ZIP、SHA-256、`artifact-manifest.json` 和 `verification-report.json`。当前部署入口见 `deployment/windows/RUNBOOK.md`，M1-13 手册保留为 `RUNBOOK-M1-13.md`。

## M1-13 身份基础阶段验收门禁

M1-13 把空库 Flyway 迁移、身份与平台角色授权、生产构建 SPA、外部 Spring Boot JAR 和全新 PostgreSQL 串成同一条可重复验收链。门禁通过真实 HTTP Session/Cookie 覆盖匿名、普通成员、`APP_MANAGER`、`COMPANY_ADMIN`、双角色、离职、禁用、退出、隐藏 404 与授权版本失效；页面组件渲染仍由现有 Vitest/happy-dom 覆盖，不引入浏览器自动化框架。

```powershell
pnpm verify:m1-13
```

`verify:m1-13` 先校验 `evidence/m1-13`，再执行既有 `verify:m0-18:portable`，随后仅使用已构建的 JAR 与 Web 产物运行外部 HTTP 门禁，并复核绑定当前 Git SHA 的新鲜报告。门禁固定占用本机 8100、18174；任一端口被占用时直接失败且不会终止无关进程。完整验证需要 Java 21、Node 24.14、pnpm 11.16、Docker Linux engine 和 `postgres:17.10-alpine`。

Windows 云服务器手工部署使用独立入口 `pnpm verify:m1-13:deployment`。它在完整 M1-13 门禁通过后生成 `out/m1-13/yumpoo-windows-m1-13.zip`，包内只保留当前 JAR/Web、Nginx、C 盘配置、PostgreSQL 初始化 SQL 与运行手册；本次验证不包含 Windows 服务包装器或 IIS。服务器以 `MANUAL_JAVA_CONSOLE` 运行，公网仅开放 443，内部固定使用 SPA 18173、后端 8100 和 PostgreSQL 5432。

受控夹具只有在 `local` 或 `test`、`m1-13-e2e` 与 `YUMPOO_M113_FIXTURE_ENABLED=true` 同时满足时才注册；混入 `prod`、身份表非空或配置不完整都会拒绝启动。夹具不开放 HTTP 写入口，只经目录成员服务、维护用例和公共平台角色命令端口创建两个固定测试成员。Project ACL 真实资源留到 M2；真实企微 OAuth、扫码与公司 HTTPS 证据留到 M6-01，现有 `ENV_PENDING/NOT_RUN` 不会被本门禁提升为 `PASS`。

## M1-12 Web 全局壳、登录态与统一错误体验

M1-12 发布正式的 Web 应用壳和集中会话状态。已认证用户可在响应式顶栏与侧栏中查看当前公司、用户和客户端类型；首页展示主体摘要，身份管理入口按生成的角色枚举显示，并保留概览、同步运行、成员管理三个直达地址。普通成员访问管理地址会进入权限拒绝页，未知地址进入真实 404 页面。

SPA 启动时以单飞请求读取 `/api/v1/auth/me`，确认登录态前不渲染受保护页面。401 会通过顶层导航启动企业微信认证，并在 `sessionStorage` 中一次性保存最多 5 分钟的安全站内返回地址；账号禁用、客户端升级要求和服务不可用分别进入壳外阻断页。禁用页不尝试退出，426 页面不虚构版本信息或下载地址；真实客户端版本策略继续由 M4-13 交付。

统一错误适配安装在生成客户端配置层，不修改生成目录，也不维护第二套 API DTO。仅精确接管 `AUTHENTICATION_REQUIRED`、`ACCOUNT_DISABLED` 和 `CLIENT_UPGRADE_REQUIRED`；普通权限拒绝、未知错误码和畸形响应保留在业务上下文。同步运行继续处理经校验的 `409 + Location`，成员 412 冲突会刷新列表与已打开详情、保留冲突提示且不自动重放写请求。Vite dev 与 preview 均把 `/api` 原样代理到本地 8100 端口。

```powershell
pnpm verify:m1-12
```

完整验证需要 Java 21、Node 24.14、pnpm 11.16，以及能运行 PostgreSQL Testcontainers 的 Docker 环境。

## M1-11 公司、企微、同步运行与成员管理

M1-11 发布只读公司与企微概览、通讯录同步运行诊断和成员管理页面。`APP_MANAGER` 与 `COMPANY_ADMIN` 均可读取管理数据；仅 `COMPANY_ADMIN` 可触发同步或启停账号。角色仅展示，页面不提供授予或撤销入口。

正式接口覆盖 `/api/v1/company`、企微状态、成员分页/详情、同步运行分页/详情/失败项以及手工同步触发。手工同步沿用同步执行模型：新意图返回 `201`，同键重放返回 `200`，不同键命中活动批次返回带 `Location` 的 `409`。成员启停继续要求 CSRF、幂等键、`If-Match` 和 1～160 字理由。

企微 Secret 仅由受控外部配置注入，生产环境不提供 API 或页面写入口。页面/API 只返回启用状态、配置完整性、脱敏 Corp ID 与凭据是否配置，不返回 Secret、token 或回调地址。

```powershell
pnpm verify:m1-11
```

## M1-08 平台/企业角色与授权策略

M1-08 新增只读的平台角色底座。`platform_role_assignment` 保存 `COMPANY_ADMIN` 与 `APP_MANAGER` 的作用域、授予/撤销事实和历史版本；`COMPANY_MEMBER` 继续由 `ACTIVE + ENABLED` User 派生，不入角色表。角色表不预置管理员，本切片也不开放授予/撤销命令、REST 或管理页面。正式写入口由后续 M1-09 在同一事务内递增 `authorization_version` 并撤销会话后再开放，业务代码和 fixture 之外不得直接写表。

会话认证成功后按 Company/User 查询有效角色，并把角色集合与当前 `authorizationVersion` 一起固化进 `CurrentActor`。`/api/v1/auth/me` 固定按 `COMPANY_MEMBER → COMPANY_ADMIN → APP_MANAGER` 返回，Spring authorities 使用同一份快照。通用授权 guard 将可见拒绝映射为 403，将需隐藏的拒绝映射为 404。

`catalog.api.ProjectAccessSnapshotQuery` 只冻结 M2 所需的最小只读契约，本轮没有 Project、membership、owner 表或生产实现。后续实现必须在 SQL 中同时按 Company 与可见范围过滤，禁止先无范围读取再在 Java 中隐藏。当前纯策略规定：成员可正常读写；非成员 `COMPANY_ADMIN` 只读、普通写 403；仅 `APP_MANAGER` 与普通非成员隐藏 404；角色兼任按能力并集处理；跨 Company 始终隐藏。

```powershell
pnpm verify:m1-08
```

完整验证需要 Docker Desktop Linux engine，以运行 PostgreSQL 17/Testcontainers 集成测试；无 Docker 时只能执行 `cd backend; .\mvnw.cmd -DskipITs test` 与 `pnpm verify:node`，不得视为完整通过。

## M1-07 账号启停与会话撤销闭环

M1-07 交付后端内部的 `AccountStatusUseCase`：账号状态与就业状态严格独立，禁用和启用命令都要求预期行版本、持久化幂等键、请求哈希和 1～160 字符的原因引用。真实状态迁移会同时递增 `row_version` 与 `authorization_version`；禁用记录操作者和原因，启用保留最近一次禁用事实，LEFT 用户允许启用账号但仍不能登录。同键同请求重放已保存结果，同键异参、陈旧版本、重复状态和跨企业访问分别按统一语义拒绝。

完整目录同步将成员置为 LEFT、手工账号禁用或启用时，会在同一事务中撤销该用户所有尚未逻辑过期的 Web/Electron 活动会话。已过期但尚未落终态的会话不会被重分类，因而继续返回 401；以 `EMPLOYMENT_LEFT` 或 `ACCOUNT_DISABLED` 撤销且仍在保留期内的旧凭据返回 403。返聘或重新启用不会恢复任何旧会话，请求已通过过滤器后发生状态变化时，`CurrentActor` 的数据库复核仍会在业务代码执行前拒绝请求。

事件契约新增 `identity.user_account_disabled` v1、`identity.user_account_enabled` v1 和用户级 `identity.user_sessions_revoked` v2；logout 使用的 v1 保持兼容。公共 payload 不包含自由文本原因，操作者与原因引用只进入 `ADMIN_OVERRIDE` actor envelope。本切片不新增数据库迁移、管理端 REST/OpenAPI、生成客户端或页面；角色、最后管理员保护、近期认证、Security Audit 与管理页面继续由 M1-08～M1-11 交付。

```powershell
pnpm verify:m1-07
```

## M1-05 通讯录部分失败、对账、离职与返聘

M1-05 在 M1-04 全量同步批次上增加成员级失败隔离和完整快照对账。完整扫描后的单成员资料或写入失败会记录稳定错误并继续处理其余成员，批次终态为 `PARTIALLY_SUCCEEDED`；任何部分失败都不会执行缺失成员离职对账。ID 扫描、部门字典、共享凭据、租约和持久化等全局故障仍将批次置为 `FAILED`。同一 trigger key 永久重放原批次，修复问题后必须以新 trigger key 发起新的全量同步。

只有扫描完整且全部发现成员成功时，最终事务才会把本次未出现的 ACTIVE WECOM 身份原子标记为 `LEFT`。若本地已有 ACTIVE 成员，供应商返回空目录会以 `DIRECTORY_EMPTY_SNAPSHOT_REJECTED` 失败关闭。相同 external ID 再次出现时复用原 User 并记为 `RETURNED`，保留禁用和最近离职事实，同时递增授权版本。事件契约新增 `identity.directory_sync_completed` v2 及就业 LEFT/RETURNED v1，payload 不包含姓名、联系方式或 external ID。

```powershell
pnpm verify:m1-05
```

该入口复核既有 M1-04 live evidence，随后执行后端 `clean verify` 和完整 Node 门禁。本切片仍为纯后端内部用例，不新增 REST/OpenAPI、页面、调度或企微离职回调；不批量修改 `login_session`，会话撤销及完整 401/403 语义留给 M1-07。

## M1-04 通讯录同步批次与全量导入

M1-04 交付纯后端的 `DirectorySyncUseCase`：以 trigger key 幂等创建同步批次，按 Company 互斥并使用 5 分钟可续租约隔离旧 worker。Flyway `V9` 创建长期批次/成员结果和 RUNNING 期资料暂存；终态会删除暂存、清空原始游标与租约，只保留 external user ID、profile hash、动作、计数和稳定错误码。该切片不增加 REST/OpenAPI、页面、定时调度、离职/返聘、成员级重试或会话撤销。

目录 ID 与成员资料使用两个独立企微 Secret。显式空 `next_cursor` 可单次确认完成；供应商省略终止游标时必须重复完整扫描，成员集合、页数和逐页摘要一致才继续。全部资料读取完成前不会修改 User；部门名按数字部门 ID 排序后以顿号汇总，手机号/邮箱缺失会保留旧值，显式空值才清除。生命周期通过 `identity.directory_sync_started/completed/failed` v1 事件发布，payload 不含个人资料、原始游标或凭据。

```powershell
pnpm verify:m1-04
```

该入口先校验 `evidence/m1-04`，再执行后端 `clean verify` 与完整 Node 门禁；PostgreSQL 集成测试要求 Docker Desktop Linux engine 可用。真实企微验证是独立的非自动门禁，证据默认保持 `ENV_PENDING`。准备两类受控 Secret、独立的至少 32 字节 HMAC 密钥，并启用 `m1-04-live` profile 与 `YUMPOO_M104_WECOM_ENABLED=true` 后，运行 `pnpm verify:m1-04:live`；runner 只提交 HMAC 指纹、布尔检查和经短期签名收据验证的 PASS 事实。

## M1-03 会话与安全底座

M1-03 交付 PostgreSQL 不透明 Web 会话、User 授权版本、Spring Security 7 安全链和数据库绑定的 Cookie/CSRF 契约。Session 与 CSRF 原文只在签发时返回一次，数据库仅保存用途隔离的 HMAC-SHA-256 指纹；会话采用 8 小时空闲、7 天绝对过期和绝对到期后 24 小时的撤销事实保留期。

`/api/v1/**` 默认要求 `__Host-yumpoo-session` 认证，写请求还需以 `X-XSRF-TOKEN` 回传可读的 `__Host-yumpoo-csrf` Cookie。两个 Cookie 均固定 `Secure`、`SameSite=Lax`、`Path=/` 且无 Domain，Session 额外启用 `HttpOnly`。本切片不新增 callback、logout、`/me` 或正式登录页面。

```powershell
pnpm verify:m1-03
```

该入口依次执行后端 `clean verify`（含 PostgreSQL 17/Flyway、会话并发、CSRF 与安全链集成测试）及完整 Node 工作区门禁。

## M1-02 User 与 ExternalIdentity 底座

M1-02 在 `identityaccess` 模块建立正式 `identity_user` 与 `external_identity` 数据模型。WECOM 外部成员标识在 Company 内唯一，且一期与 User 严格一对一；姓名、邮箱和手机号仅为当前目录资料，变化时复用原 User，不参与身份合并。就业状态与账号状态分别持久化，目录资料刷新不会隐式改变任一状态。

模块内部的 `DirectoryMemberProvisioningService` 使用唯一 Company 配置和 PostgreSQL 事务级 advisory lock 串行化同一外部身份的建立/刷新，避免并发首次同步产生重复绑定或孤儿 User。该切片不新增 REST/OpenAPI、前端、同步批次、会话、账号治理、角色或事件发布。

```powershell
pnpm verify:m1-02
```

该入口依次运行后端 `clean verify`（含 PostgreSQL 17/Flyway、并发建立、数据库约束及备份恢复验证）和完整 Node 工作区门禁。

## M1-01 Company 与工作日历底座

M1-01 在 `organization` 模块交付单 Company 与工作日历的后端底座。数据库迁移 `V6` 固定种子为 `Yumpoo`、`Asia/Shanghai`、周一周起始和 480 分钟默认工作日，并以数据库约束保证单 Company、日历日期唯一及工作日分钟语义。运行期只从数据库读取 Company 配置；缺失、非法 IANA 时区或非周一起始配置都会失败关闭。

跨模块只通过 `organization.api` 的只读查询契约取得 Company 配置和解析后的日历快照。缺省规则为周一至周五工作、周末休息，显式覆盖优先；日期计算与本地时刻解析不依赖服务器默认时区，并固定处理 DST 缺口和重叠。本步不新增 REST/OpenAPI、前端或日历管理命令。

```powershell
pnpm verify:m1-01
```

该入口依次运行后端 `clean verify`（含 PostgreSQL 17/Flyway、架构、日历边界与备份恢复验证）和完整 Node 工作区门禁。

## M0-18 最小 CI 与开发证据包

M0-18 只交付验证编排、GitHub Actions 门禁和开发证据治理，不新增业务 API、数据库迁移或前端 DTO。项目的完整验证链与生产等价运行时只覆盖 Windows x64，需要 Node.js 24.14.0、pnpm 11.16.0、Java 21、PowerShell，以及运行 Linux container 的 Docker；OpenAPI 默认从 `origin/dev` 提取历史契约，基线缺失、为空或不可解析时直接失败。

```powershell
pnpm install --frozen-lockfile
pnpm test:m0-18
pnpm verify:m0-18
```

也可按 CI 边界分别运行 `pnpm verify:m0-18:portable` 与 `pnpm verify:m0-18:windows`，但二者是 CI 分段入口，不能替代 Windows x64 上的 `pnpm verify:m0-18`。`M0 Portable Gate` 在 Ubuntu 24.04 只执行可移植门禁：OpenAPI 兼容性、Maven Verify、Flyway/Testcontainers、ArchUnit、Node 构建与测试、100 MiB 探针和备份恢复；它随后用逐文件大小与 SHA-256 manifest 交付已测试的 JAR/Web 字节，不声明 Linux 运行时或生产等价性。`M0 Windows x64 Gate` 必须等待 portable 成功，复核同一提交和 handoff 精确文件集后，在 Windows 2022 执行真实 Electron smoke、Electron Windows 打包、ASAR 白名单、M0-16 ZIP 组装及复核。packaged-JAR、回环监听、外部配置、目录/数据库故障语义与脱敏拒启 smoke 只由完整 Windows x64 入口执行。

最终开发证据写入忽略目录 `out/m0-18/evidence-pack` 并由 CI 作为 30 天 artifact 上传。报告以 `validationMode` 区分 `WINDOWS_X64_FULL` 与 `WINDOWS_X64_CI_STAGE`；FULL 报告还必须消费绑定当前提交与 JAR 摘要的 server-smoke receipt。CI 分段报告必须把 `serverSmoke` 记为 `NOT_RUN`、使用 `pnpm verify:m0-18:windows` 作为复现命令，并带上 `WINDOWS_FULL_CHAIN_NOT_RUN` 限制，绝不冒充完整验证。包内只允许 verification report、延期清单、portable handoff manifest、M0-15/M0-16 manifest、ZIP 摘要和 M0-17 三份安全元数据；JAR、ZIP、附件、dump、日志、测试 XML、绝对路径、环境变量和任何凭据均被拒绝。动态 `PASS` 报告绑定实际测试提交，不进入 Git。

`evidence/m0-18/deferred-acceptance.json` 与仓库内所有 live evidence 双向精确对账：当前 M0-12、M0-14、M0-15、M0-16 保持 `NOT_RUN`，M0-13 已 `PASS` 因而不得列入延期集合。真实企微 OAuth、扫码登录、系统浏览器交接与公司 HTTPS 统一在 M6-01 部署/发布环境门禁补验；Defender/NTFS、干净 Windows Server/IIS，以及 M0-17 的计划任务、异机复制、告警、Secret 恢复、真实 Schema 恢复、保留清理和 RPO/RTO 演练仍是 M5/M6 环境或运维门禁。M0 开发门禁通过不代表这些 live 验收已经完成。

## M0-17 数据库与附件成套备份/隔离恢复原型

M0-17 以测试与验证工具交付可重复的本地恢复闭环，不新增生产备份命令、业务表或 HTTP API。门禁使用两个独立的 `postgres:17.10-alpine` Testcontainers 实例，在源实例内执行 `pg_dump -Fc`，完整验证备份集后才向全新目标实例执行 `pg_restore`。合成引用表只存在于测试数据库；附件复用 M0-14 的 SHA-256 内容寻址目录并包含一个只报告、不删除的孤儿样本。

```powershell
pnpm verify:m0-17
```

备份集同时包含 PostgreSQL custom dump、正式附件 blob、普通配置恢复样例和不含 Secret 值的恢复描述。`manifest.json` 最后写入并精确覆盖全部载荷，记录应用/PostgreSQL/Flyway 版本、公司时区、源码提交、文件角色、字节数和 SHA-256；绝对路径、路径穿越、反斜杠、符号链接、Windows 大小写碰撞、缺件、额外文件和篡改都会失败关闭。恢复要求目标数据库无 `yumpoo` schema、附件目录为空，完成后复核 Flyway 版本、合成引用、大小、哈希和实际可读字节。

`evidence/m0-17` 只跟踪 manifest、retention plan 和 verification report 的严格 JSON Schema 与合成示例。每次门禁的新鲜备份集和 `PASS` 报告写入忽略目录 `out/m0-17`，不提交环境绑定快照。保留规划仅 dry-run 选择 14 daily、8 weekly、6 monthly，支持多标签与 legal hold，绝不删除文件。外部介质、Windows 计划任务、失败告警、真实业务恢复以及 RPO 24 小时/RTO 4 小时演练仍属于 M5/M6。

## M0-16 Windows 部署资产与本地运行门禁

M0-16 交付可机审的 Windows Server 2022 x64 开发部署资产，以及完整的本地构建、运行和发布包复核门禁。它不会真实配置 Nginx、注册 WinSW 服务、修改 ACL/防火墙，也不会把目标服务器证据写成 `PASS`。目标机验收和签名收据留给 M5-14/M6；当前 `evidence/m0-16/live-verification.json` 必须严格保持 `NOT_RUN`。

```powershell
pnpm verify:m0-16
```

门禁依次检查 Windows x64、Java 21、Docker 与工具链，校验 `deployment/windows` 和 M0-16 证据，执行完整 `verify:m0-15` 回归，组装发布 ZIP，点名启动 packaged JAR，最后重新解包复核白名单、逐文件哈希和 ZIP 哈希。输出位于：

- `out/m0-16/yumpoo-windows-m0-16.zip`
- `out/m0-16/yumpoo-windows-m0-16.zip.sha256`

ZIP 包含后端 JAR、Vite 生产构建、普通配置与 Secret 占位模板、Nginx/WinSW 模板、历史 IIS 回退模板、供应链锁定信息和运行清单；不包含真实 Secret、WinSW 二进制、source map 或源码绝对路径。`artifact-manifest.json` 列出除自身以外的全部包内载荷，路径排序并记录字节数与 SHA-256。

生产 profile 固定只监听 `127.0.0.1:8100`，Nginx 的静态 SPA listener 固定为 `127.0.0.1:18173`，公网 virtual server 仅通过 443 暴露 `wecom-dev.yumpoo.com`。后端关闭 forwarded-header 解析并启用 45 秒 graceful shutdown。readiness 同时反映数据库和附件/临时上传/日志目录写入状态；这些依赖故障时 readiness 为 503/DOWN，而 liveness 保持 200/UP。Windows 参数化模板和 dry-run 清单见 `deployment/windows/RUNBOOK.md`。

## M0 验收口径

M0 将本地/CI 可重复的开发门禁与依赖外部条件的环境门禁分开：`pnpm verify:m0-*` 证明协议、持久化、安全边界、构建与证据格式；真实企业微信 OAuth、公司 HTTPS、真实 Defender/NTFS、干净 Windows Server、Nginx、服务账号 ACL、仅 443 和整机重启必须在对应环境中另行证明。未执行时 live evidence 保持 `NOT_RUN`，不阻塞本地开发，也绝不等于 `PASS`。

M0 不实现正式企业微信扫码登录，不创建正式 User、ExternalIdentity、LoginSession 或可续期会话。正式 Web 身份与会话能力属于 M1，正式 Electron 认证与桌面会话属于 M4；两者在本地使用仅限 local/test 的受控身份提供者验证，真实企微 OAuth、扫码、鉴权与公司 HTTPS E2E 统一在 M6-01 部署/发布环境门禁完成。

## M0-15 Electron 浏览器交接与安全壳验证

M0-15 的开发门禁验证 Electron 复用唯一远程 SPA、系统浏览器交接协议、PKCE、一次性 handoff、自定义协议处理和最小安全壳，不提前交付 M4 的正式桌面会话，也不要求真实企微登录。真实系统浏览器企微 OAuth、公司 HTTPS SPA 与 `yumpoo://` 的端到端证据统一属于 M6-01 部署/发布环境门禁；M4-14 只验证受控身份提供者下的本地桌面语义。后端诊断能力默认不存在；只有 profile 列表包含 `m0-15-live` 且 `YUMPOO_M015_WECOM_ENABLED` 严格等于 `true` 时才注册以下非 OpenAPI 路径：

- `GET /_m0/m0-15/electron/auth/authorize`，接收 `state`、`codeChallenge` 和固定的 `codeChallengeMethod=S256`。
- `GET /_m0/m0-15/wecom/callback`，完成企微成员检查后跳转 `yumpoo://auth/callback`。
- `POST /_m0/m0-15/electron/auth/exchange`，只接收 `code`、`state`、`codeVerifier` 并返回短期 HMAC 签名的脱敏收据。

真实后端从受控外部环境读取以下配置；不得把真实值写入仓库、日志、命令输出或工单：

- `YUMPOO_M015_WECOM_CORP_ID`
- `YUMPOO_M015_WECOM_AGENT_ID`
- `YUMPOO_M015_WECOM_APP_SECRET`
- `YUMPOO_M015_WECOM_CALLBACK_URI`（固定、不带 query/fragment 的同源 HTTPS callback）
- `YUMPOO_M015_WECOM_ALLOWED_MEMBER_IDS`
- `YUMPOO_M015_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种字符，独立于企微 Secret）
- `YUMPOO_WEB_URL`（packaged app 加载的唯一公司 HTTPS SPA）

自动门禁从仓库根目录运行：

```powershell
pnpm verify:m0-15
```

该命令先严格校验 `evidence/m0-15` 的 Schema、示例和当前证据，再调用 `package:m0-15:win` 生成 Windows x64 packaged app、扫描 ASAR 白名单，并为可运行目录中的每个文件生成和复核 SHA-256 manifest，最后串联最新的 `verify:m0-14`；不会从 M0-12 重复建立另一条回归链。普通实现和 PR 允许 `live-verification.json` 保持 `NOT_RUN`。

真实验证只接受一次真实系统浏览器企微登录、公司 HTTPS SPA、packaged app 和 `yumpoo://auth/callback` 共同产生的短期收据。除上述后端变量外，还须由受控 live harness 提供：

- `YUMPOO_M015_LIVE_BASE_URL`（与 callback、`YUMPOO_WEB_URL` 同源的 HTTPS origin）
- `YUMPOO_M015_AUTH_RECEIPT_PATH`（本次 exchange 原始响应的短期文件）
- `YUMPOO_M015_DESKTOP_RECEIPT_PATH`（绑定本次认证收据与构建 manifest 的 HMAC 桌面收据）
- `YUMPOO_M015_BUILD_MANIFEST_PATH`（本次 packaged app 的 manifest）

上述路径必须是绝对路径。证据 HMAC 密钥只进入受控后端/live harness，绝不传入 Electron 应用。准备完成后运行：

```powershell
pnpm verify:m0-15:live
```

当前 live runner 是安全 preflight 与收据验证入口，不模拟企微登录、不代签桌面收据。它只在 Windows x64、双门禁、同源 HTTPS、近期后端签名认证收据、近期域分离桌面收据和实际 manifest 摘要全部匹配时，才原子更新证据为 `PASS`；失败时不改原证据，并始终删除两份短期收据。最终证据只保存 Windows/架构、Electron 版本、固定协议回调、manifest SHA-256 和布尔检查，不保存 code、state、verifier、身份指纹、requestId、签名、路径、Cookie、token 或 Secret。自动 live harness 尚未串联或真实流程未执行时必须保持 `NOT_RUN`，不得手工改成 `PASS`。

本切片不发布正式 `/api/v1/electron/auth/*`、不创建 User、ExternalIdentity、LoginSession 或可续期桌面凭据，也不实现 Windows 凭据存储、通知流、托盘业务、版本阻断、安装器、自动更新或离线业务能力；这些仍属于 M1/M4 或 M0 后续退出验证。

## M0-14 安全附件工程验证

M0-14 冻结的是可复用的文件安全技术核心，不是正式附件业务功能。生产代码提供固定缓冲流式接收、100 MiB（`104857600` bytes）硬上限、增量 SHA-256、文件名净化、Apache Tika 内容识别、ZIP/OOXML 区分、恶意内容扫描端口、Microsoft Defender `MpCmdRun` 适配器，以及隔离区到同卷内容寻址目录的 `ATOMIC_MOVE`。只有服务端识别类型与扩展名、声明 MIME 一致且扫描结果明确为 `CLEAN` 时，内容才可转为 `AVAILABLE`；超限、类型不符、威胁、扫描超时/未知结果和中断均失败关闭。

本切片不新增生产 Attachment Flyway 表，也不发布正式 `/api/v1/attachments` OpenAPI path。PostgreSQL 表、父对象授权桩和 `/api/v1/__test/m0-14/attachments` 都只存在于测试源码，用来证明短事务、异步扫描、回滚孤儿安全、下载前再次授权和无权隐藏 404；正式元数据、业务授权、配额、删除/清理、调度及 Activity/Outbox 留给 M2。公共契约仅补齐既有 `FILE_TOO_LARGE`（413）与 `FILE_TYPE_NOT_ALLOWED`（415）的 golden response。

```powershell
pnpm verify:m0-14
```

该门禁会先校验证据文件，再以 `-Xmx96m` 点名执行 100 MiB 懒生成流探针，最后串联 `verify:m0-13` 的完整 OpenAPI、后端 PostgreSQL/Testcontainers、Node、桌面与前端回归。Docker 不可用时真实 PostgreSQL 验收会失败，不会回退到 H2。普通开发与 PR 允许 `evidence/m0-14/live-verification.json` 保持 `NOT_RUN`；真实 Defender/NTFS 证据在具备受控 Windows 环境后补验，并在 M5/M6 发布门禁前完成。

真实环境验证必须确认允许使用 EICAR 测试串，并从外部注入以下变量；不要把路径、密钥或扫描器输出提交到仓库或工单：

- `YUMPOO_M014_LIVE_ENABLED=true`
- `YUMPOO_M014_ALLOW_EICAR=true`
- `YUMPOO_M014_LIVE_ROOT`（已存在、空间充足的 NTFS 目录）
- `YUMPOO_M014_DEFENDER_EXECUTABLE`（`MpCmdRun.exe` 的绝对路径）
- `YUMPOO_M014_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节且至少 8 种字符）

```powershell
pnpm verify:m0-14:live
```

live runner 只在配置目录下创建一次性 `m0-14-live-*` 子目录；它验证近上限干净样本、EICAR 失败关闭、NTFS 同卷原子移动和中断清理，校验短期 HMAC 收据后才原子更新脱敏证据，并始终删除短期收据。Defender 退出码 `2` 同时可能表示威胁或扫描错误，因此适配器保守映射为 `INDETERMINATE`，绝不解析本地化控制台文本来猜测“干净”。

YumpooPlatform 一期采用单部署的模块化单体后端、共享 Vue SPA，以及只加载同一在线 SPA 的 Electron 桌面壳。

## M0-13 企微通讯录安全验证骨架

```text
backend/                     Spring Boot 模块化单体
contracts/openapi/           OpenAPI 3.0.3 唯一契约与错误样例
contracts/events/            内部事件信封 Schema、事件目录与探针样例
frontend/web-app/            Vue 3 在线 SPA
desktop/desktop-shell/       Electron main/preload 在线壳
packages/api-client/         由 OpenAPI 生成的 TypeScript Fetch SDK
packages/preload-contract/   Web 与 preload 共享的最小类型契约
tools/architecture/          Node 工作区边界门禁
tools/openapi/               lint、生成漂移和兼容性检查工具
tools/events/                事件目录、Schema 与正反样例校验工具
tools/verification/          契约生成、三端联合验证与桌面冒烟
evidence/m0-12/              真实企微验证的 Schema、示例与脱敏证据
evidence/m0-13/              通讯录验证的 Schema、示例与脱敏证据
```

本仓库使用 Java 21、Maven Wrapper 3.9.9、Node.js 24.14.0 和 pnpm 11.16.0。Node 工作区只有根目录一份 `pnpm-lock.yaml`，所有声明依赖均锁定精确版本。后端数据库基线是 PostgreSQL 17.10、Spring JDBC 和 Flyway，业务对象统一进入 `yumpoo` schema。

## 安装与联合验证

```powershell
pnpm install --frozen-lockfile
pnpm verify:m0-13
pnpm smoke:desktop
```

`verify:m0-13` 先校验 M0-13 真实验证证据的 Schema、示例和当前文件，再串联 `verify:m0-12` 的全部门禁：M0-12 证据、事件目录、JSON Schema 与正反样例、OpenAPI、生成客户端及生成物漂移、后端 Maven Verify，以及 Node 工作区的 Lint、类型检查、边界负向测试、单元测试和生产构建。后端 Verify 会通过 Testcontainers 启动 `postgres:17.10-alpine`；Docker 不可用时直接失败，不使用 H2 或跳过真实库验收。`smoke:desktop` 在随机回环端口启动已构建的 Vue SPA，并让隐藏的 Electron 窗口完成一次真实加载后正常退出。

也可以分别验证：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
pnpm validate:event-contracts
pnpm check:openapi
pnpm generate:api-client
pnpm check:api-client
pnpm check:openapi-compat -- <baseline-openapi-file>
pnpm verify:node
```

本次新增的契约在首次合入 `dev` 后即成为初始兼容性基线。`check:openapi-compat` 使用固定的 openapi-diff 2.1.6；传入历史 OpenAPI 文件后，任何不兼容变更都会失败，M0-18 再把基线提取和该命令接入 PR CI。

## M0-12 企微环境验证（延期门禁）

M0-12 诊断路由默认不存在。只有同时设置 `SPRING_PROFILES_ACTIVE=m0-12-live` 和 `YUMPOO_M012_WECOM_ENABLED=true` 时，后端才注册以下路径：

普通开发与 PR 只要求 `pnpm verify:m0-12` 通过。它使用可控测试边界验证 OAuth 参数、corp/member 映射、state/nonce、并发消费、伪造/重放拒绝、供应商失败映射和脱敏；不要求真实成员授权或扫码、公开域名和真实 HTTPS 回调。以下 live 流程在受控联调环境具备后执行，最迟在 M6 候选版本冻结前完成；此前证据保持 `NOT_RUN`。

- `GET /_m0/m0-12/wecom/authorize`
- `GET /_m0/m0-12/wecom/callback`

运行真实验证前，在后端进程和验证脚本所在的受控环境中配置数据库变量，以及以下值；真实值、授权 code、token、Secret 和完整 callback query 不得写入仓库、命令输出或工单：

- `YUMPOO_M012_WECOM_CORP_ID`
- `YUMPOO_M012_WECOM_AGENT_ID`
- `YUMPOO_M012_WECOM_APP_SECRET`
- `YUMPOO_M012_WECOM_CALLBACK_URI`（固定 HTTPS callback，不带 query）
- `YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS`（逗号分隔的测试成员白名单）
- `YUMPOO_M012_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种字符，不得使用占位值或复用应用 Secret）

先通过现有 HTTPS 反向代理启动启用了 `m0-12-live` profile 的后端，再在具有相同配置的另一终端执行：

```powershell
pnpm verify:m0-12:live
```

验证脚本默认从 callback URI 的 origin 访问服务，也可用同源 HTTPS 的 `YUMPOO_M012_LIVE_BASE_URL` 覆盖。脚本会自动检查伪造 state、错误 nonce、无效 code 和已消费 attempt 重放均返回统一 401，然后要求同一白名单成员完成两次授权；粘贴的单行签名收据不会回显。只有两份收据验签通过且企业、成员指纹稳定一致后，脚本才会把 `evidence/m0-12/live-verification.json` 从 `NOT_RUN` 更新为脱敏 `PASS`。该证据不保存原始身份、requestId、签名、code、token 或完整供应商响应；未在真实企业环境执行时必须保持 `NOT_RUN`。

## M0-13 企微通讯录真实验证

M0-13 live runner 采用双重门禁。只有 profile 列表包含 `m0-13-live` 且 `YUMPOO_M013_WECOM_ENABLED` 严格为 `true` 时才允许运行。请只在已把 Java 直连企微时的出口公网 IP 加入企微可信 IP、且服务账号可读取受控环境变量的非生产测试环境中注入以下五项。`HTTP_PROXY/HTTPS_PROXY` 环境变量不会自动改变本 live harness 的 Java 出口；通过代理查询到的公网 IP 不能代替实际直连 IP。

- `SPRING_PROFILES_ACTIVE`（包含 `m0-13-live`）
- `YUMPOO_M013_WECOM_ENABLED=true`
- `YUMPOO_M013_WECOM_CORP_ID`
- `YUMPOO_M013_WECOM_DIRECTORY_SECRET`（通讯录同步 Secret，不得复用 OAuth 应用 Secret 的配置名）
- `YUMPOO_M013_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种 Unicode code point，不含 `change-me`、`changeme`、`placeholder`、`password` 或 `secret-key`，且不得复用通讯录 Secret）

不要把上述真实值写入 PowerShell 历史、仓库、工单、日志或聊天。配置完成后从仓库根目录执行：

```powershell
pnpm verify:m0-13:live
```

Node runner 会先删除任何遗留收据，再精确执行 Maven 测试类 `M013WeComDirectoryLiveVerification`。为保证 live 验证不连接业务数据库，Maven 子进程会继承 Java、Docker、PATH 和五项 M0-13 配置，但显式移除遗留的 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 与 `SPRING_FLYWAY_URL/USER/PASSWORD`。Java 在任何 probe 对账前先完成两次 `limit=1` 的真实窄页扫描和一次 `limit=10000` 的宽页扫描：两次窄页都必须观察真实非空游标，三次都必须以供应商省略终止游标结束，且企业、快照和成员 HMAC 集合完全一致。随后才验证重跑幂等、分页失败保护、成员级部分失败、合成离职/返聘和脱敏限制，并在 `backend/target/m0-13-live-receipt.json` 写入一次短期 HMAC 签名收据。收据签名输入固定为 UTF-8 `receipt\0` 域前缀加 canonical 正文，不能与 collector 的 corp/member/snapshot HMAC 消息空间复用。Node runner 校验严格字段白名单、运行时间窗、全部布尔检查和签名后，才原子地把 `evidence/m0-13/live-verification.json` 更新为 `PASS`；无论成功或失败都会删除收据，任何失败都不会覆盖原证据。

M0-13 证据只保存企业与目录快照的不可逆 HMAC、运行时间、执行时 M0-12 证据状态和布尔检查，不保存人数、页数、游标、原始成员 ID、个人资料、Secret、token、签名或完整供应商响应。`providerPaginationObserved` 证明窄页真实分页，`providerTerminalCursorOmissionConfirmed` 证明三份脱敏快照已交叉确认供应商省略终止游标。M0-12 状态只如实记录为 `NOT_RUN` 或 `PASS`，不阻断 M0-13 的独立通讯录验证；未完成真实验证时 M0-13 证据必须保持 `NOT_RUN`。人工诊断中输出的通讯录或原始 userid 不得写入仓库、证据、日志或提交说明。

`externalLimitsRecorded` 固定验证以下企微只读契约，不把运行时数量写入证据：通讯录 ID 拉取使用 `POST /cgi-bin/user/list_id`，请求 `limit` 只能在 1～10000；官方契约以空 `next_cursor` 表示结束，但本次真实企业调用观察到终止页直接省略该字段。主 `DirectorySnapshotCollector` 对缺失/null 游标始终返回 `Incomplete(MISSING_CURSOR)`，只有 M0-13 live 在已经观察真实分页、两次窄页与一次宽页 HMAC 快照完全一致时才把它作为测试专用候选快照，M1 正式同步必须重新冻结生产语义。该接口必须使用通讯录同步 Secret，且此 Secret 只读取成员 ID，不读取姓名、手机、邮箱等成员资料。`/cgi-bin/gettoken` 正常返回 7200 秒有效期，客户端必须缓存并提前刷新，不能逐页换 token。错误 `-1`、`45009` 可退避重试；`40001`、`48002`、`60020` 是凭据、权限或可信 IP 配置失败，不得盲目重试；`40014`、`42001` 只允许失效缓存并刷新 token 后重试一次。冻结依据为企微官方的[获取成员 ID 列表](https://developer.work.weixin.qq.com/document/path/96067)、[通讯录接口调整说明](https://developer.work.weixin.qq.com/document/path/96079)、[获取 access_token](https://developer.work.weixin.qq.com/document/path/91039)和[全局错误码](https://developer.work.weixin.qq.com/document/path/90313)。

## 本地开发

完整的数据库环境变量、端口表和启动顺序以 `docs/30-operations/local-development.md` 为本地开发基线。

本机无法使用企业微信 OAuth 与通讯录同步时，可显式启用回环地址限定的本地免登录身份。后端会通过现有身份、角色与会话服务预置“本地测试管理员”，授予 `COMPANY_ADMIN + APP_MANAGER`，并在首次 `/api/v1/auth/me` 请求签发正常的 Session/CSRF Cookie；SPA 因而不会先进入 `/login`，后续读写仍执行正式鉴权与 CSRF 校验。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:YUMPOO_LOCAL_AUTH_ENABLED = 'true'
```

默认登录成员为 `local-company-admin`，显示名为“本地测试管理员”。需要固定其他身份时可设置 `YUMPOO_LOCAL_AUTH_MEMBER_ID`、`YUMPOO_LOCAL_AUTH_DISPLAY_NAME`、`YUMPOO_LOCAL_AUTH_BACKUP_MEMBER_ID` 与 `YUMPOO_LOCAL_AUTH_BACKUP_DISPLAY_NAME`。该模式默认关闭，只允许 `local` profile、`127.0.0.1`/`localhost`/IPv6 loopback，并拒绝与 `prod`、企微 OAuth、企微通讯录或受控身份提供者同时启用；已有治理数据会复用当前可用 APP_MANAGER，只有没有可用管理员时才按状态执行首管引导或紧急恢复。恢复正常认证时移除上述两个变量并清理浏览器本地 Cookie。

先启动在线 SPA：

```powershell
pnpm dev:web
```

另开终端编译并启动桌面壳：

```powershell
pnpm dev:desktop
```

Web 开发服务器默认监听 `http://127.0.0.1:18173`，Vite Preview 默认监听 `http://127.0.0.1:18174`。Electron 开发模式复用同一个 Web SPA，默认加载 `http://127.0.0.1:18173`；`YUMPOO_WEB_URL` 可以覆盖地址。开发环境只接受 `localhost` 或 `127.0.0.1` 的 HTTP 地址，生产环境必须提供无用户名密码的 HTTPS 地址。

后端程序和 Windows 生产模板的安全默认值均为 `127.0.0.1:8100`，需要其他本机端口时可通过 `YUMPOO_SERVER_PORT` 覆盖。运行后端还需通过环境变量提供应用数据库连接：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

共享和生产环境还必须设置独立迁移账号的 `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD`。所有密码都从外部配置注入，不进入仓库。M0-12 与 M0-13 都不新增正式生产业务端点；未启用诊断双重门禁时仅对外提供：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 架构边界

- 后端 13 个一级模块统一采用 `api/application/domain/infrastructure` 四层；ArchUnit 对层级方向、允许依赖矩阵、跨模块内部实现访问和循环依赖执行硬门禁。
- Web/renderer 不得导入 Node、Electron 或 desktop-shell 实现；可运行时依赖浏览器 Fetch 边界的 `@yumpoo/api-client`，只能以 type-only 方式读取 `@yumpoo/preload-contract`。
- OpenAPI 是请求、响应、错误、分页和客户端生成的唯一契约源；生成目录禁止手工修改，漂移由验证脚本阻止。
- preload 仅在唯一入口通过三个固定认证通道包装 `ipcRenderer`，不暴露原始 IPC 或 Node built-in；Renderer 只看到冻结的 `window.yumpooDesktop` 最小桥。
- Electron main/preload/Web 分离编译；新窗口、权限请求和跨源导航默认拒绝。

## 当前与后续范围

M0-11 在既有幂等事务底座上增加稳定内部事件信封、`outbox_event`、`outbox_consumer_receipt` 和可配置 dispatcher。`TransactionalEventPort` 只能加入现有事务，业务失败时事实、幂等记录与事件一并回滚；HTTP 根请求的 `requestId` 同时成为 `correlationId`，消费与派生事件继续继承关联链路。内部事件契约位于 `contracts/events`，本切片只登记测试探针事件，不增加正式 HTTP 路径或 OpenAPI operation。

M0-12 增加可复用的企微身份网关、持久化 OAuth attempt 和仅用于真实验证的双门禁诊断路由。state 与浏览器 nonce 都只以哈希持久化，attempt 在访问企微前原子消费；成功只返回 HMAC 签名脱敏收据，不创建 User、ExternalIdentity 或 LoginSession。`DEPENDENCY_UNAVAILABLE` 是公共 503 契约，但诊断路由本身不进入正式 OpenAPI paths。

M0-13 增加通讯录读取适配器和仅供验证的同步对账探针。真实调用验证受可信 IP 保护的通讯录凭据与供应商分页行为；可重复的本地场景验证中途失败不触发离职对账、单成员失败可诊断、重跑不重复建立身份，以及同一外部标识的合成离职/返聘复用。该切片不创建正式 Company、User、ExternalIdentity、DirectorySyncRun 或管理 API，正式同步批次、游标、暂存和会话撤销仍由 M1 实现。

worker 默认每秒轮询，批量 50、并发 2、租约 5 分钟。领取覆盖到期的 `PENDING/RETRY` 与租约过期的 `PROCESSING`，并以 owner + token 防止旧 worker 回写；低版本未完成或 `DEAD` 会阻塞同聚合高版本。每个消费者的数据库效果与 receipt 在独立事务中提交，多消费者重试会跳过已完成者；五档退避后第六次失败进入 `DEAD`。控制台使用 Spring Boot 内建 Logstash JSON 日志，并在请求和消费边界写入受控关联字段。

正式身份绑定与会话、Security Audit、Activity 投影、通知投递、人工重排、监控告警、Outbox 清理和管理页面仍留给后续切片。完成 M0-13 也不代表完整 M0 里程碑退出。
## M1-09 角色治理与管理员紧急恢复

M1-09 在后端新增 `APP_MANAGER` 与 `COMPANY_ADMIN` 的分页查询、授予和撤销应用端口，但不注册正式 HTTP/OpenAPI。角色写入要求操作者是同公司的可用 `APP_MANAGER`，会话授权版本仍为当前值，且企微登录签发时间不早于命令执行前 15 分钟；过期后必须重新登录。角色变化、目标用户授权版本递增、Web/Electron 会话以 `AUTHORIZATION_CHANGED` 撤销、幂等结果和 Outbox 事件处于同一事务。

公司级 `app_manager_governance_state` 既是一次性首管闩锁，也是所有角色变化、账号启停和目录离职/返聘的并发互斥点。主动撤销或禁用最后一名可用 `APP_MANAGER` 会被拒绝；企微目录离职不会被阻塞，人数从 1 降为 0 时产生持久缺失事件，administration 投影为 `GovernanceIssue`，恢复后保留已解决历史。

首管引导和 break-glass 仅通过默认关闭的非 Web 维护 Runner 执行，不提供匿名或回环 HTTP。Windows 使用方式见 `deployment/windows/RUNBOOK.md` 与 `deployment/windows/Invoke-AppManagerMaintenance.ps1`。Security Audit、失败审计和角色写 HTTP 仍属于 M1-10，管理页面属于后续切片。

从仓库根目录执行完整门禁：

```powershell
pnpm verify:m1-09
```

## M1-10 Security Audit 与身份治理 HTTP

M1-10 新增 append-only 的 `security_audit_event`，以 Company + fact key 去重，并提供 Company + requestId 的内部分页查询端口。登录、退出、批量会话撤销、目录同步/离职返聘、账号启停、角色变更、首管/break-glass 及 APP_MANAGER 缺失/恢复均写入最小脱敏审计摘要。高风险成功审计与 User、Session、Outbox 和幂等结果同事务；业务拒绝回滚后以独立事务记录 FAILED，审计不可写时失败关闭并返回安全的 `INTERNAL_ERROR`。

正式 HTTP 已开放治理快照、角色查询/授予/撤销和成员账号启停，写请求统一要求 Session、CSRF、`Idempotency-Key`、`If-Match`、15 分钟近期认证及 1～160 字符理由。TypeScript 客户端由 OpenAPI 生成；本切片不开放审计查询 HTTP，也不包含管理页面、CapabilityAssignment、哈希链或 WORM 导出。

```powershell
pnpm verify:m1-10
```
