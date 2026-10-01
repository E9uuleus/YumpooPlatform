# Agent Note: 连接列与连接的独立聚合、锁序与事件投影

Status: implemented

## Problem

跨项目投递需要由来源项目定义连接列，并允许通过连接查看目标工作项的最小卡片。普通关系要求双侧成员写入、隐藏不可见对端，且其 v1 事件枚举已经冻结；把连接加入关系表会让读取授权随类型分支变化，也无法表达列删除时解除整列连接的归属关系。

## Decision

连接列 `ConnectColumn` 与连接 `WorkItemConnection` 是 workitem 模块内的独立聚合。V62 追加目录版本行、列、列目标和连接四张表；不修改普通关系表、服务或事件。列名在项目内按小写规范化后唯一，写入、更新、查重和 CHECK 统一使用 PostgreSQL `lower()`，避免 Java 与数据库对 Unicode 大小写的不同处理；目录行串行化列管理。每项目最多 20 个有效列，每列 1–20 个其他项目目标，每个来源工作项在每列最多 50 条有效连接。同列同端点的有效连接由部分唯一索引守护，重复关联返回 200 与既有 ID；不同列允许相同端点。

列保存项目共享的名称和目标集合，不保存列顺序、宽度、显隐、排序、分组或筛选配置。删除列在同一事务软删全部有效连接并记录 `COLUMN_DELETED`，仅发一条列删除事件；手工解除记为 `UNLINKED`。工作项删除不级联连接，读取在 SQL 内省略已删除端点，恢复工作项后连接重新出现。项目归档不级联连接，也不把连接作为归档 blocker；任一端归档后拒绝创建和解除。

写入遵守项目 UUID 字符串升序 → 连接列目录 → 列 → 内容目录与类别 → 工作项（项目 UUID、工作项 UUID 升序）→ 连接的锁序，各命令只获取其需要的锁。项目成员端使用 `ProjectFactWriteGuard`，不要求成员的目标端使用 catalog 公开端口 `ProjectConnectionTargetQuery.lockAsConnectionTarget`；两者均与项目归档的排他锁互斥。获得项目锁后重新读取访问与生命周期。列共享锁保护目标集合，等待列更新后另查目标行，避免等待前的查询快照继续认可已移除目标。

新建并关联通过 `WorkItemService.createRootItemForConnection` 的 MANDATORY 入口复用 `createItem`，内部只引入 `ItemWriteTarget(companyId, projectId, projectCode)`，沿用编号、标签、秩、项目顺序和工作项事件逻辑。普通根项、子项和连接创建共用类别加锁入口：首次使用先取得内容目录锁，再锁类别，避免持有类别共享锁等待目录时发生锁升级死锁；已使用类别只取共享锁，不占用内容目录管理锁。`createItem` 在秩通道、项目顺序和工作项写入前统一完成 `everUsed` 标记，连接入口不复制该过程。失败时类别标记、目录版本、工作项与事件一起回滚。普通创建参数重构单独提交并有根项、子项和处理人校验回归。

连接通过 `lockProjectItemIncludingDeleted(companyId, projectId, workItemId)` 直接锁定端点，再显式检查删除与归档：创建遇到删除返回 404，归档返回 `WORK_ITEM_ARCHIVED`；解除允许端点工作项已删除。锁查询不以预读的类别 ID 限定工作项，等待期间移动类别不会误报 404。既有 `lockProjectItem` 会直接过滤归档项，不适合连接所要求的 409 语义，因此保留其现状与普通关系调用方。

列重名使用既有 422 `name/DUPLICATE` 字段错误；目标项目无启用类别与选择停用类别统一返回 422 `contentId/CONTENT_NOT_ACTIVE`。解除在事务内完成授权和有序加锁后，先判断连接或列是否失效，再校验 ETag；已解除或被删除列级联解除的连接返回 409 `CONNECTION_NOT_ACTIVE`，仍有效的连接版本不匹配返回 412。

移除目标的冲突仍只公开契约规定的 `targetProjectId` 和 `activeConnectionCount`。字段及其业务判断由 workitem 构造，foundation 通过 `ApplicationException.withSafeDetails` 传递显式审查的公开标量，不认识连接领域类型。附加详情不可覆盖 `reason`、`blockers`，拒绝任意对象和嵌套结构，复制为不可变映射后由错误适配器平铺；没有附加详情的既有错误形状不变。该机制不授权新字段，端点仍受各自闭合错误 schema 约束。

连接事件使用 `workitem.connect_column_created/updated/deleted` 与 `workitem.connection_created/deleted` v1，聚合类型分别为 `ConnectColumn` 和 `WorkItemConnection`。命名不使用 `workitem.work_item_` 前缀，不进入 M2 冻结清单或 `WORK_ITEM_EVENTS`。载荷只含约定标识、列名、变更字段或聚合计数，不含工作项标题、描述和正文。Activity 通过独立 `CONNECTION_EVENTS` 订阅：列事件写入所属项目；连接事件在两侧各投影一次，来源侧仅以事件列名渲染，目标侧只引用自身工作项且安全参数为空，audit 不为连接渲染回查工作项。连接不产生通知和单元格级动态。

## Alternatives considered

- 向 `WorkItemRelation` 增加 CONNECT 类型：拒绝。可见性、目标非成员投递和列归属都与普通关系不同，还会破坏冻结事件枚举。
- 复制工作项创建过程或直接插入工作项：拒绝。编号、标签校验、排序、首次使用和事件会出现两套行为；使用同一创建内核和事务可整体回滚。
- 按来源、目标角色顺序锁项目：拒绝。相反方向连接会形成反向锁序；按 UUID 排序使两个方向一致。
- 删除列时逐条发送解除事件：拒绝。列删除本身已携带解除数量，逐条事件会重复 Activity；连接墓碑仍保留具体删除原因。
- 在 Activity 中查询列当前名或工作项正文：拒绝。事件中的列名足够渲染，回查会使重放依赖当前状态并增加跨项目信息暴露。
- 为连接建立通用自定义列框架：拒绝。本期只有已确认的连接列能力，不为未来类型增加空实现或持久化偏好。
- 在 foundation 中保存连接专用记录，或自动序列化任意异常对象为详情：拒绝。前者倒置模块职责，后者可能泄露内部状态；仅允许应用层显式提供契约定义的公开标量。

## Consequences

连接的访问与卡片授权由[连接投递与卡片可见性](../security/2026-09-30-connection-intake-and-card-visibility.md)拥有；[普通关系可见性](../security/2026-08-31-cross-project-work-item-relation-visibility.md)继续拥有关系的隐藏占位规则，两者均保持活动，不发生完整取代或归档。

`ConnectionConcurrencyIT` 验证双向关联、双向新建、普通与连接创建首次使用类别的锁序、已使用类别不等待目录锁、等待期间类别移动、列删除竞争、归档先后顺序、等待期间失权、列目标更新后的复核和失败整体回滚；`ConnectionMigrationIT` 从 V59 存量场景升级并确认 V62 保留既有事实。`ConnectionEventProjectionIT` 检查五类实际事件字段和两侧投影，`ConnectionActivityProjectionTest` 确认渲染不回查工作项。OpenAPI 只新增端点、schema 和黄金样例，不需要破坏性例外；后续扩字段或改变授权仍需独立决策与兼容审查。
