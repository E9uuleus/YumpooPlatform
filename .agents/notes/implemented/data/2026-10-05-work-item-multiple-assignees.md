# Agent Note: 工作项多处理人、组合分组与成员统计

Status: implemented

## Problem

单处理人不能表达共同拥有工作项，也会让共同处理人缺失于筛选、个人计时候选与通知。将一项在每位处理人的分组中重复显示会冲突于行身份、勾选和排序；按全员耗时统计每位成员则重复归属其他成员的投入。已有主处理人字段、旧客户端、游标和冻结事件需要兼容。

## Decision

V64 的 work_item_assignee 以公司、项目、工作项、用户与 position 保存最多 20 位平等处理人；position 0 镜像 work_item.assignee_user_id，无处理人时镜像为 NULL。两者在同一事务维护，关联表通过工作项范围外键级联硬删除，备份恢复覆盖关联表。保留成员维持原顺序，新增成员按请求顺序追加；仅重排请求为无变化。只验证新增成员是项目 ACTIVE 成员，保留已离开项目的既有处理人不阻断其他字段编辑。

API 只增加可选 assignees 响应与 assigneeUserIds 请求、PATCH /assignees；旧幂等响应缺少列表时前端回退到主处理人。新列表优先；旧全量更新传当前主处理人时保留完整列表，其他非空 ID 设为单人，null 清空；旧 PATCH /assignee 始终设为单人或清空。列表排序、游标锚点及空值仍使用主处理人。有序列表的生成客户端保持数组，精确集合查询使用 Set；生成后处理沿用现有 exactOptionalPropertyTypes 兼容机制。

处理人筛选、连接候选和「指派给我」匹配集合中任一成员。按处理人分组使用 ASSIGNEE_SET 与 assigneeSetUserId 精确集合，每项只出现于一个组合组；组内新增写入整组处理人。组合键为小写 UUID 字符串升序以逗号连接，SQL 使用 UUID 序，Java 使用 UUID.toString() 的字符串序，避免 UUID.compareTo 的有符号差异；标签按显示名排序以「、」连接。

Outbox 的 aggregate_type/aggregate_id/aggregate_version/event_type 唯一键不允许同一命令发多条同类 assigned v1。冻结 assigned/unassigned v1 只描述主处理人变化，并总是追加变更后 assigneeUserIds；集合变化另发一条 workitem.work_item_assignees_changed v1，创建只在 created v2 附完整列表。消费者用 payload.has("assigneeUserIds") 标记跳过新生产的 assigned/unassigned，包括空数组；无标记的历史事件照旧消费。综合 Activity 每命令一条摘要，单元格按每个增减成员拆行；V65 把成员 ID value_key 纳入投影唯一键。通知只向 addedUserIds 或 created.assigneeUserIds 投递 ASSIGNED，评论候选包含全部当前处理人，仍排除操作人并复核权限与账号状态。

仪表板成员维度或系列按处理人展开，成员耗时只计本人在被指派事项上的原始记录；非处理人耗时只进入总计及其他维度，未分配组使用全员耗时。全局或本地成员筛选同时收窄成员展开。filtered/scoped 保持每工作项一行，聚合才展开，明细与下钻继续显示全员累计耗时；非成员横轴的分类排序也保持每项一次。

## Alternatives considered

- 数组列替换旧单列：需要迁移旧客户端、排序和索引语义，关联表与主处理人镜像能保留兼容边界。
- 每成员发布 assigned v1：违反 Outbox 唯一键；按人数推进 rowVersion 让事件各占版本会令 ETag 跳号，均拒绝。
- 改为 assigned v2：需要双版本消费迁移，选择可选标记与独立集合事件保留历史 v1。
- 在每位处理人组重复显示同一项：同一行身份无法同时承担多组拖动、勾选和草稿，采用唯一组合组。
- 向每位成员计入事项全员耗时：会重复归属投入，成员统计使用本人记录。

## Consequences

主处理人兼容值与完整列表必须共同维护；组合分组与成员统计分别承担唯一行展示和共同归属。集合变化事件订阅需与生产者对账，冻结的 14 类清单不变。新数组仍为可选响应属性，避免旧幂等重放与 mock 缺字段时反序列化失败。

此记录部分取代[私人仪表板](../product/2026-09-16-personal-dashboards.md)的单处理人成员口径，并补充[核心合同](../architecture/2026-08-22-work-item-core-contract.md)、[事件冻结](2026-08-31-work-item-event-contract-freeze.md)与[字段分组](../product/2026-09-14-work-item-field-grouping.md)。
