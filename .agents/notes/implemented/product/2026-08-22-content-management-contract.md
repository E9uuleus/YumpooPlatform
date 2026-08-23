# Agent Note: Content 管理与强类型 View Config 契约

Status: implemented

## Problem

Project 固定模板需要落成用户可管理的 Content 容器，同时保持工作项类型来源可信、归档并发安全以及后续 Table/Kanban 的配置可演进。若客户端直接提交工作项类型或任意 JSON，模板约束会失效；若 Content 写入不参与 Project 行锁协议，则 Project 归档可与新事实同时提交；若在尚无 Work Item 真源时报告零 blocker，又会制造错误保证。

## Decision

Content 创建只接受项目内唯一代码、名称、描述和 `blueprintCode`。服务端从 Project 固定的模板 key/version 查找蓝图，派生不可变的工作项类型、初始默认视图和模板来源；同一 Project 允许多个 Content 使用相同工作项类型。既有 Project 固定到 RETIRED 模板后仍可继续按该版本创建 Content。

Content 写事务先通过 catalog 的 `ProjectFactWriteGuard` 获取 Project `FOR SHARE` 锁并复核主体访问，再对既有 Content 获取 `FOR UPDATE`。Owner 独占写权限；Member 和 CompanyAdmin 只读；同公司不可见主体及跨公司访问返回 404。归档 Project 可读但禁止 Content 写入。Content 只允许 `ACTIVE -> ARCHIVED -> ACTIVE`，归档状态不可编辑，等价规范化 PATCH 不推进版本也不发布事件。

`ContentViewConfig` 是递归关闭的强类型 wire 契约，顶层只允许 `table` 和 `kanban`，规范化 JSON 上限为 16 KiB。历史 `{}` 在读取时展开为稳定默认值；列缺项按默认顺序补齐，TITLE 不可隐藏，排序字段不重复且最多三个，状态筛选必须属于固定模板。显式 Kanban 分组必须且只能覆盖模板全部状态一次。PATCH 完整替换可变详情并在比较前规范化。

M2-13 继续复用 `ContentViewConfig.table.filters/sort` 作为 Content 共享默认，不新增私人视图或新的 wire/event 版本。所有可读用户都可在 URL 中临时使用完整筛选与排序；只有 Project Owner 可通过现有 Content PATCH 保存共享默认，Member 和 CompanyAdmin 保持只读。共享默认写入继续发布现有 `workitem.content_updated`，普通查询不发布事件。

M2-14 保留 `kanban.statusGroups` 的配置语义，但每个分组在工作区中拆成单状态子泳道。分组数量展示所属泳道数量之和；rank、分页、锁和投放能力始终以单一状态为边界。状态筛选排除的子泳道保留结构提示但不可投放，避免把隐藏状态误当成合并泳道。

创建、归档和恢复使用 UUID 幂等键；PATCH、归档和恢复使用强 ETag。四类 `workitem.content_*` 事件只携带 Content 标识、Project、代码、名称、类型、状态、默认视图、蓝图、版本和变更字段，不携带描述或完整视图配置。Content 容器本身不构成 `OPEN_WORK_ITEMS` blocker；M2-10 的真实 Work Item 计数已同时阻止 Content 与 Project 归档。

## Alternatives considered

- 由客户端提交 `workItemType` 和默认视图：拒绝。会绕过 Project 固定模板并破坏来源审计。
- 对 `view_config` 只验证为 JSON 对象：拒绝。未知字段、状态漂移和不完整 Kanban 分组会把兼容风险推给后续渲染器。
- 为 M2-09 注册永远返回零的 Work Item blocker：拒绝。Content 容器不是开放工作项，零计数必须来自真实 Work Item 查询。
- 用鼠标拖拽作为唯一列排序方式：拒绝。配置抽屉使用上移/下移按钮，保证键盘和触控可操作。
- 将临时查询写入用户资料或本地私有视图表：拒绝。URL 已提供可恢复、可分享且无新增持久化生命周期的临时状态。

## Consequences

模板蓝图、工作项类型和 Content 来源成为服务端权威；客户端只消费目录给出的蓝图和状态选项。未来增加列、筛选器或看板配置时必须以兼容新增方式扩展 OpenAPI、Java 规范化器和生成 SDK，未知字段仍被旧服务端拒绝。M2-10 已沿用 `ProjectFactWriteGuard` 的锁顺序，并把真实 OPEN_WORK_ITEMS provider 接到 Project 归档治理；Content 归档在持有 Content 排他锁时检查同一真源。

Web 工作台复用现有 `--yp-*` 令牌、Element Plus 模态框/抽屉以及 Light、Dark、Night 与双密度机制；桌面使用连续语义表格，窄屏重组为卡片。Content 配置页与工作项工作区使用同一查询编辑器；无 URL 自定义状态时读取共享默认，重置时清除查询参数。412 冲突保留本地草稿并加载最新基线；查询默认的明确重提只合并 filters/sort，不能覆盖并发列配置或 Kanban 分组。
