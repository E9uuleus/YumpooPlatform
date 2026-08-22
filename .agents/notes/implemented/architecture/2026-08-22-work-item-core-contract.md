# Agent Note: Work Item 编号、锁顺序与安全事件契约

Status: implemented

## Problem

Work Item 是跨 Content、Project 生命周期与归档治理的长期事实。编号若在应用内先查后增会在并发下重复；创建若不与 Project/Content 归档共享锁协议会产生归档后写入；blocker 若复制数据或固定报告零会漏算；事件若携带正文会扩大敏感信息传播面。Web 同时需要让 Table 与 Kanban 消费同一真源，而不提前开放后续编辑和排序语义。

## Decision

`work_item` 保存完整领域列，M2-10 写接口只开放标题、明确优先级、描述和备注。描述与备注规范化为最多 16 KiB 的纯文本；展示使用 Vue 文本插值。处理人、自然日、PATCH/ETag、状态迁移、复杂筛选排序和 rank/拖拽不在本契约中。

事项编号按 Project 独立计数器原子递增，固化为 `PROJECT_CODE-sequence`。编号不复用，Project 改名或后续代码策略变化都不改写既有编号。幂等记录包住编号分配、Work Item 插入和 Outbox；同键同请求重放存储的 201 响应，不再次推进计数器或发布事件。

创建事务固定按 Project `FOR SHARE`、Content `FOR SHARE`、模板固定版本、Project 计数器、Work Item 的顺序执行，并在锁内复核 Project 与 Content 生命周期。Content 归档按 Project `FOR SHARE`、Content `FOR UPDATE` 后查询开放事项；两条事务因此只能得到“事项先创建并阻止归档”或“Content 先归档并使创建失败”。Project 归档的排他锁同样与创建互斥。

workitem 通过公开只读端口向 administration 报告 `OPEN_WORK_ITEMS`，直接统计未删除且状态类别为 `TODO/IN_PROGRESS` 的真源行。普通 Project 归档显示安全聚合数量，治理覆盖沿用同一数量写入 `admin_override`。已声明 provider 缺失、异常或不完整时关闭失败。

`workitem.work_item_created` v1 只携带 Work Item、Project、Content、编号、标题、类型、状态、优先级、报告人和版本摘要；不携带 description、notes 或未来协作正文。Table 使用 Content 的列顺序和显隐并固定按事项序号倒序分页；只读 Kanban 按配置状态组独立请求同一列表端点，不保存第二份卡片数据，也不表达 rank。

## Alternatives considered

- 使用 `max(sequence)+1` 或在 JVM 内加锁：拒绝。多实例和事务回滚下无法建立单调唯一保证。
- 用数据库全局 sequence 生成编号：拒绝。会泄露跨 Project 写入节奏，也不满足 Project 内连续、可读的编号语义。
- 归档先提交、再异步扫描并补偿 Work Item：拒绝。会允许归档后成功写入，且用户无法得到确定的 409。
- 把 description/notes 放入创建事件方便下游搜索：拒绝。正文可能含客户或内部信息，搜索投影应通过显式授权读取真源或未来的安全专用事件建立。
- 在 M2-10 同时开放拖拽和状态迁移：拒绝。没有 rank 与迁移命令的并发/权限契约时，视觉交互会承诺尚不存在的写语义。

## Consequences

备份恢复必须共同保留 Work Item、Project 计数器、固化编号、状态类别、正文和审计字段。任何新增 Work Item 写入口都必须复用相同 Project→Content 锁顺序；任何改变“开放”定义的状态语义都必须同步修改 blocker、索引、治理证据和兼容测试。

Web 可在 M2-11 至 M2-14 增量增加协作字段、迁移与 rank，但不得把当前固定序号排序解释为 Kanban 排名。完整 PPM-014 仍依赖 Worklog 和 Feedback provider，于 M2-24 验收；M2-10 只确认 `PPM-014-OPEN-WORK-ITEMS`。
