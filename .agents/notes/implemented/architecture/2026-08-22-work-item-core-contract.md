# Agent Note: Work Item 编号、字段并发与安全事件契约

Status: implemented

## Problem

Work Item 是跨 Content、Project 生命周期与归档治理的长期事实。编号若在应用内先查后增会在并发下重复；创建或字段更新若不与 Project/Content 归档共享锁协议会产生归档后写入；字段更新若只依赖最后写入会静默覆盖协作者；blocker 若复制数据或固定报告零会漏算；事件若携带正文会扩大敏感信息传播面。Web 同时需要让 Table 与 Kanban 消费同一真源。

## Decision

`work_item` 保存完整领域列。创建与 M2-11 字段更新使用标题、明确优先级、处理人、描述、备注、计划起止日和截止日的快照；PATCH 的可空字段必须显式传 `null` 才清空。描述与备注规范化为最多 16 KiB 的纯文本；自然日使用 `LocalDate`/`YYYY-MM-DD`，不经过服务器或浏览器本地时区换算。既有 v1 创建契约继续允许旧客户端省略新增处理人和日期，初始值按空处理；新 Web 固定提交完整八字段。状态迁移、复杂筛选排序、rank/拖拽和删除恢复仍不在本契约中。

事项编号按 Project 独立计数器原子递增，固化为 `PROJECT_CODE-sequence`。编号不复用，Project 改名或后续代码策略变化都不改写既有编号。幂等记录包住编号分配、Work Item 插入和 Outbox；同键同请求重放存储的 201 响应，不再次推进计数器或发布事件。

创建事务固定按 Project `FOR SHARE`、Content `FOR SHARE`、模板固定版本、Project 计数器、Work Item 的顺序执行，并在锁内复核 Project 与 Content 生命周期。Content 归档按 Project `FOR SHARE`、Content `FOR UPDATE` 后查询开放事项；两条事务因此只能得到“事项先创建并阻止归档”或“Content 先归档并使创建失败”。Project 归档的排他锁同样与创建互斥。

字段更新事务固定按 Project `FOR SHARE`、Content `FOR SHARE`、Work Item `FOR UPDATE` 加锁，再校验调用人、预期版本、ACTIVE assignee membership 与字段约束，最后执行带 `row_version` 条件的更新。Owner 与 ACTIVE Member 只可编辑 DRAFT/ACTIVE Project 的 ACTIVE Content；非成员 CompanyAdmin 为 403，不可见资源为 404，归档资源为 409。强 ETag 由 `rowVersion` 派生；无实际变化返回原资源和 ETag，不改变版本、审计时间或 Outbox；旧 ETag 返回 412，绝不自动重放。

workitem 通过公开只读端口向 administration 报告 `OPEN_WORK_ITEMS`，直接统计未删除且状态类别为 `TODO/IN_PROGRESS` 的真源行。普通 Project 归档显示安全聚合数量，治理覆盖沿用同一数量写入 `admin_override`。已声明 provider 缺失、异常或不完整时关闭失败。

`workitem.work_item_created` v1 兼容增加可选处理人和自然日。每次有效 PATCH 发布 `workitem.work_item_fields_changed`；分配、改派与取消分配另发 assigned/unassigned v1。事件只携带稳定引用、安全标量、`changedFields` 和新版本，不携带 description/notes 正文；创建时已有处理人只发 created。Table 使用 Content 的列顺序和显隐并固定按事项序号倒序分页；只读 Kanban 按配置状态组独立请求同一列表端点，不保存第二份卡片数据，也不表达 rank。

## Alternatives considered

- 使用 `max(sequence)+1` 或在 JVM 内加锁：拒绝。多实例和事务回滚下无法建立单调唯一保证。
- 用数据库全局 sequence 生成编号：拒绝。会泄露跨 Project 写入节奏，也不满足 Project 内连续、可读的编号语义。
- 归档先提交、再异步扫描并补偿 Work Item：拒绝。会允许归档后成功写入，且用户无法得到确定的 409。
- 把 description/notes 放入创建事件方便下游搜索：拒绝。正文可能含客户或内部信息，搜索投影应通过显式授权读取真源或未来的安全专用事件建立。
- 发生 412 后由 Web 自动以最新 ETag 重放：拒绝。会把用户未审阅的草稿覆盖到新事实之上；必须保留草稿并要求载入最新或明确重提。
- 在 M2-10 同时开放拖拽和状态迁移：拒绝。没有 rank 与迁移命令的并发/权限契约时，视觉交互会承诺尚不存在的写语义。

## Consequences

备份恢复必须共同保留 Work Item、Project 计数器、固化编号、状态类别、处理人、自然日、正文、更新审计字段和 rowVersion。任何新增 Work Item 写入口都必须复用相同 Project→Content→Work Item 锁顺序；任何改变“开放”定义的状态语义都必须同步修改 blocker、索引、治理证据和兼容测试。

Web 不得把当前固定序号排序解释为 Kanban 排名。状态迁移、高级查询、rank、删除恢复和 Activity 投影继续由 M2-12 至 M2-20 增量交付。完整 PPM-014 仍依赖 Worklog 和 Feedback provider，于 M2-24 验收；当前只确认 `PPM-014-OPEN-WORK-ITEMS` 与 M2-11 协作字段切片。
