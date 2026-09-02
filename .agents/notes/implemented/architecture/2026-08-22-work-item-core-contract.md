# Agent Note: Work Item 编号、字段/状态并发与安全事件契约

Status: implemented

## Problem

Work Item 是跨 Content、Project 生命周期与归档治理的长期事实。编号若在应用内先查后增会在并发下重复；创建、字段更新或状态迁移若不与 Project/Content 归档共享锁协议会产生归档后写入；协作写入若只依赖最后写入会静默覆盖；客户端若自行推测迁移边会绕过 Project 固化模板版本；blocker 若复制数据或固定报告零会漏算；事件若携带正文会扩大敏感信息传播面。Web 同时需要让 Table 与 Kanban 消费同一真源。

## Decision

M2-19A 起，Project 级状态/优先级标签目录成为运行时可选值、展示和排序真源；受保护初始状态、停用/删除及粗统计兼容语义见 [Project 级标签目录决策](../data/2026-08-26-project-work-item-label-catalog.md)。下文关于固定模板迁移图和固定优先级秩的描述仅适用于 M2-19A 前事实及模板初始化来源。

`work_item` 保存完整领域列。创建与 M2-11 字段更新使用标题、优先级、处理人、描述、备注、计划起止日和截止日的快照；`priority` 与其他可空字段必须显式出现，传 `null` 表示暂不设置或清空。摘要、详情、事件和数据库列共同接受空优先级，V39 只移除 `priority` 的非空约束而不改写既有值。描述与备注规范化为最多 16 KiB 的纯文本；自然日使用 `LocalDate`/`YYYY-MM-DD`，不经过服务器或浏览器本地时区换算。既有 v1 创建契约继续允许旧客户端省略新增处理人和日期，初始值按空处理；新 Web 固定提交完整八字段。

事项编号按 Project 独立计数器原子递增，固化为 `PROJECT_CODE-sequence`。编号不复用，Project 改名或后续代码策略变化都不改写既有编号。幂等记录包住编号分配、Work Item 插入和 Outbox；同键同请求重放存储的 201 响应，不再次推进计数器或发布事件。

创建事务固定按 Project `FOR SHARE`、Content `FOR SHARE`、模板固定版本、Project 计数器、Work Item 的顺序执行，并在锁内复核 Project 与 Content 生命周期。Content 归档按 Project `FOR SHARE`、Content `FOR UPDATE` 后查询开放事项；两条事务因此只能得到“事项先创建并阻止归档”或“Content 先归档并使创建失败”。Project 归档的排他锁同样与创建互斥。

字段更新事务固定按 Project `FOR SHARE`、Content `FOR SHARE`、Work Item `FOR UPDATE` 加锁，再校验调用人、预期版本、ACTIVE assignee membership 与字段约束，最后执行带 `row_version` 条件的更新。Owner 与 ACTIVE Member 只可编辑 DRAFT/ACTIVE Project 的 ACTIVE Content；非成员 CompanyAdmin 为 403，不可见资源为 404，归档资源为 409。强 ETag 由 `rowVersion` 派生；无实际变化返回原资源和 ETag，不改变版本、审计时间或 Outbox；旧 ETag 返回 412，绝不自动重放。

M2-12 初始实现按 Project 固化模板读取迁移边；M2-19A 后模板只负责目录初始化，运行时允许在所有启用状态间迁移，目标具体状态与颜色来自 Project 标签目录，自定义状态使用兼容类别 TODO。状态命令仍按 Project → Content → 排序后的源/目标 lane → Work Item 加锁，专用条件更新改变 `status_code/status_category/rank`、更新审计字段和 `row_version`，并把事项放到目标状态顶部；所有协作字段保持不变。

`WorkItemCapabilities.availableTransitions` 是服务端按 Project 标签目录顺序计算的唯一客户端迁移选项源；只读、归档或无权限时为空，停用状态不可作为新目标。迁移要求 XSRF、强 `If-Match` 与持久化幂等键；同键同请求精确重放原 200 响应，同键异参冲突。状态、一条 Outbox 事件与幂等完成记录在同一事务提交；重放、非法目标和并发失败不发新事件。

M2-13 列表查询先完成 Project/Content 可见性校验，再验证状态和排序；M2-19A 后状态与优先级合法值及秩来自 Project 标签目录。标题包含搜索大小写不敏感且转义 SQL LIKE 通配符；同字段值取 OR、不同字段取 AND，截止区间包含首尾，`updatedAfter` 严格大于。排序最多三层且字段来自 `ContentSortField`，重复、未知或格式错误统一返回 422；所有排序最后追加 `id ASC`，未传新参数的旧客户端继续按事项序号倒序。

`GET /projects/{projectId}/work-items` 在完成 Project 可见性判断后聚合该 Project 全部 Content 的非删除 Work Item。它使用独立的 Keyset Cursor 契约 `ProjectWorkItemCursorPage`，默认 25、最大 100，执行 `limit + 1` 且不做 OFFSET/COUNT；版本化 Base64URL 游标绑定 Project、视图、查询指纹和末行的完整排序元组，下一页直接以该不可变快照 seek，不因锚点随后被编辑而漂移，篡改、跨 Project 或换筛选复用均返回 422。服务端先批量加载 Content 与参与人员，再逐行计算可见性、字段编辑、讨论、项目排序和状态迁移能力，禁止逐事项查询。项目列表项是专用轻量模型，只包含表格/看板字段、ETag 和能力，明确不携带描述、备注、评论、附件或时间线详情；这些数据只在打开详情、讨论或附件区域时分别查询。优先级升降序都将空值放在末尾，指定优先级筛选不包含空值。项目 Kanban 必须恰好指定一个状态，并固定按 `updatedAt DESC, id ASC` 使用每泳道独立游标。

M2-21 后上述项目与 Content 列表的“非删除 Work Item”进一步限定为没有活动父子入边的根项，列表项批量携带直接未删除子项数；子项只经父项直接子项接口加载。五类同项目普通关系、原子换父、解除和永久两层模型的完整理由由 [单层父子关系决策](../data/2026-08-28-work-item-parent-child-relations.md) 持有。

V40 为每个活动 Work Item 增加 Project 范围的 39 位 `project_sort_key`，回填保持原 `item_sequence DESC, id ASC` 默认顺序。该顺序由所有 Project 成员共享，与 Content Kanban 的 `rank` 字段、锁和事件语义完全隔离。项目排序命令只提交筛选后可见的前后锚点；服务端锁定专用 Project 顺序记录后重新读取移动项与锚点，并在真实全局相邻间隙中分配中点。普通移动只更新被移动事项的排序键和版本，不改变 `updatedAt`、不发布业务事件；间隙耗尽时只等距重排目标附近最多 100 条，局部维护不推进其他事项版本或审计时间。新事项放在项目手工顺序首部，延续原“新事项优先”语义。归档 Content 的事项仍可见并可作锚点，但自身不可拖动。

项目列表的处理人、优先级与截止日期使用三个强 ETag、幂等的单字段命令，状态继续走模板迁移命令；因此表格编辑不需要先读取完整详情。显式 Sort 最多三层并以 `id` 作稳定末键；存在显式 Sort 时 Web 必须先清除 Sort 并重新读取手工顺序后才能拖动。搜索、人员和字段筛选可以保留，拖动只提交当前可见相邻项；未加载边界不能直接落下，Web 接近边缘时继续读取下一游标。筛选弹窗不得用已加载的 25 行冒充项目全集：专用筛选选项接口在当前查询上下文中按字段聚合动态计数，选项自身也采用默认 25、最大 100 的绑定游标。处理人编辑器的关键字通过 Project 成员接口 `q` 在数据库中匹配显示名并返回准确分页计数，前端本地过滤只作为请求失败时的降级显示。

人员排序不跨模块 JOIN。workitem 只从已经判定可见的 Content 提取报告人和处理人 ID，再通过 identityaccess 的公开 `MinimalUserSnapshotQuery` 获取当前显示名，并在分页 SQL 中注入确定性秩；无法解析的历史人员和未指派值始终置后，不允许分页后内存重排。行查询与计数由同一个 SQL 谓词构建器生成。V30 仅增加 Content 范围的更新时间、处理人和截止日部分索引，不启用 `pg_trgm`。

M2-14 的 rank 只在单一 `(content_id, status_code)` lane 内有意义。V31 将活动事项按升级前 `item_sequence DESC, id ASC` 顺序回填为 39 位定长十进制字符串，保留首尾哨兵空间，并用活动 lane 唯一约束和 Kanban 分页索引守住事实。每个 Content/状态都有可延迟创建、随 Content 级联删除的锁记录，空泳道也能串行化创建与迁移；多 lane 永远按状态码排序获取。新事项和普通迁移置顶，普通字段更新不改 rank。

项目聚合 Kanban 不定义 Project 级或跨 Content rank。卡片只可沿 `availableTransitions` 移入合法目标状态，继续复用既有状态迁移命令；命令在目标 Content 的状态泳道内置顶，但聚合泳道重新读取时仅按最近更新时间排序。由此不会把互不相干的 Content rank 拼成虚假的全局顺序，也不提供聚合看板手工重排。

M2-15 的删除是保留稳定 ID、编号、全部字段、状态与历史 rank 的墓碑写入。Owner 与 ACTIVE Member 作为“所有可写成员”均可删除和恢复；CompanyAdmin 始终只读，非成员对活动项和墓碑都得到隐藏 404。删除理由去除首尾空白后必须为 1–500 字；删除写入时间、操作者、理由并增版，恢复清空当前删除事实并增版。普通 GET、分页、Kanban、参与人排序、开放项 blocker 与活动 rank 查询显式排除墓碑；只有删除/恢复命令可通过专用仓储入口定位并锁定墓碑。

删除沿用 Project → Content → Work Item 锁序；恢复固定为 Project → Content → 状态 lane → Work Item。所有 Work Item 写命令都在锁内复核父 Project/Content 可写，任一父资源归档均返回 409。恢复保持原状态；历史 rank 未被活动项占用时直接复用，否则在 lane 锁内分配泳道顶部。V34 用生成列 `active_lane_rank = CASE WHEN deleted_at IS NULL THEN rank END` 和可延迟唯一约束守住活动 rank 唯一，同时允许任意墓碑保留重复历史 rank。

rank move 支持 `START/BEFORE/AFTER/END`。相对定位锚点必须是同 Content、同目标状态且不是自身；因此分页或筛选场景可只用可见锚点定位，未加载/隐藏事项保持原相对顺序。服务端移除移动项后计算相邻整数中点；无间隙时在 lane 锁内等距重平衡全泳道。重平衡不推进其他事项版本、不改审计时间、不发事件。同位置请求返回原 ETag，形成无版本、无事件 no-op。

`GET /contents/{contentId}/work-items?view=KANBAN` 必须恰好指定一个状态，拒绝 Table sort，并固定按 `rank ASC, id ASC` 分页。摘要公开 `rowVersion`、`etag` 与 `canMoveInKanban`。`POST /work-items/{id}/rank-moves` 要求 XSRF、强 If-Match 和幂等键；同事项竞争由版本产生一个成功与一个 412，不同事项由 lane 锁串行化。同状态有效排序发布 `workitem.work_item_rank_changed` v1，事件仅描述目标状态和定位意图、不暴露内部 rank；跨状态只发布既有 status-changed，避免重复 Activity 语义。

workitem 通过公开只读端口向 administration 报告 `OPEN_WORK_ITEMS`，直接统计未删除且状态类别为 `TODO/IN_PROGRESS` 的真源行。普通 Project 归档显示安全聚合数量，治理覆盖沿用同一数量写入 `admin_override`。已声明 provider 缺失、异常或不完整时关闭失败。

`workitem.work_item_created` v1 兼容增加可选处理人和自然日。每次有效 PATCH 发布 `workitem.work_item_fields_changed`；分配、改派与取消分配另发 assigned/unassigned v1。每次有效状态迁移发布 `workitem.work_item_status_changed` v1，载荷只含 Work Item/Project/Content 引用、编号、标题、类型、前后状态及类别、说明和新版本，不携带 description/notes 正文。普通成员迁移不额外写 Security Audit，Activity 投影仍由 M2-20 交付。普通查询不发布事件。Table 使用 Content 的列顺序和显隐及当前查询；Kanban 保留配置分组但按单状态子泳道分页，不保存第二份卡片数据。

为支持新切点后的单元格动态，`workitem.work_item_fields_changed@v1` 兼容增加可选 `previousTitle`、`previousPriority`、`previousDueDate`，当前生产者固定写入（可空字段以 JSON null 表示）；正文和备注仍严格禁止进入事件。处理人前值继续以 assigned/unassigned 专用事件为真源，状态前值继续以 status-changed 的 from/to 为真源，避免同一事实重复投影。Content 仅作为事件发生时类别快照，不新增工作项跨 Content 移动命令或事件。

删除和恢复分别发布 `workitem.work_item_deleted/restored` v1；删除事件只传播必要标识、状态、优先级、删除事实和新版本，恢复事件传播恢复操作者/时间与新版本，两者都不传播 description、notes 或内部 rank。命令要求 XSRF、强 If-Match 和持久化幂等键；同键同请求精确重放原墓碑/恢复结果且不重复发事件，新键与错误生命周期冲突返回 409。Web 只维护当前页面内存中的多条即时撤销提示，不建立持久回收站；传输失败重试复用原键，409/412 只刷新真源且不自动重提。

## Alternatives considered

- 使用 `max(sequence)+1` 或在 JVM 内加锁：拒绝。多实例和事务回滚下无法建立单调唯一保证。
- 用数据库全局 sequence 生成编号：拒绝。会泄露跨 Project 写入节奏，也不满足 Project 内连续、可读的编号语义。
- 归档先提交、再异步扫描并补偿 Work Item：拒绝。会允许归档后成功写入，且用户无法得到确定的 409。
- 把 description/notes 放入创建事件方便下游搜索：拒绝。正文可能含客户或内部信息，搜索投影应通过显式授权读取真源或未来的安全专用事件建立。
- 发生 412 后由 Web 自动以最新 ETag 重放：拒绝。会把用户未审阅的草稿覆盖到新事实之上；必须保留草稿并要求载入最新或明确重提。
- 在 M2-10 同时开放拖拽和状态迁移：拒绝。没有 rank 与迁移命令的并发/权限契约时，视觉交互会承诺尚不存在的写语义。
- 为每个用户创建私人视图：拒绝。当前产品只需要 Content 共享默认与 URL 临时查询，新增持久化真源会引入未要求的权限、同步和清理语义。
- 直接 JOIN identityaccess 用户表或分页后按显示名重排：拒绝。前者破坏模块边界，后者破坏跨页稳定性和计数语义。
- 用连续整数位置或每次移动改写所有事项版本：拒绝。前者在并发插入时频繁全列更新，后者制造无业务意义的审计与事件噪声；稀疏中点和无间隙重平衡把内部维护与业务版本分开。
- 为项目聚合看板增加跨 Content 全局 rank：拒绝。它会引入第二套排序真源、跨 Content 锁和迁移语义；聚合视图只承诺状态迁移与最近更新顺序。
- 让项目聚合列表继续复用 `WorkItemPage` 或把完整详情塞入每行：拒绝。OFFSET 在并发插入与长列表拖动下会重复或跳项，完整详情会把评论、附件和正文成本放大到滚动路径；项目列表必须使用绑定查询的 Keyset 游标与轻量行模型。

## Consequences

备份恢复必须共同保留 Work Item、Project 计数器、固化编号、状态类别、处理人、自然日、正文、更新审计字段、删除时间/操作者/理由和 rowVersion。任何新增 Work Item 写入口都必须复用相同 Project→Content 锁前缀，并按是否操作 lane 选择既定后续锁序；任何改变“开放”定义的状态语义都必须同步修改 blocker、索引、治理证据和兼容测试。

Content Kanban 只从拖动手柄启动 Pointer 交互，并提供等价的键盘/触控菜单；项目 Table 则允许从行内非交互区域启动整行拖动，名称、讨论、头像和编辑器不会误触。状态筛选排除的泳道不可投放。要求说明的跨状态移动先确认，取消不产生乐观位移。提交期间只允许一个移动；成功刷新源/目标泳道与 Table 真源，失败恢复快照。传输失败的明确重试复用原幂等键；409/412 只刷新事实与能力，不自动重提；迟到响应不得覆盖新事实。M2-21/M2-22 已完成同项目与跨项目普通关系及失权端脱敏，M2-20 Activity 与 M2-23/M2-24 的事件冻结和阶段收口均已交付。完整 PPM-014 仍依赖 M3A-13 Worklog 与 M3B-11 Feedback provider；当前确认 `PPM-014-OPEN-WORK-ITEMS`、M2-11 协作字段、M2-12 状态迁移、M2-13 高级查询、`WORK-ITEM-KANBAN-RANK`、软删除恢复与普通关系。
