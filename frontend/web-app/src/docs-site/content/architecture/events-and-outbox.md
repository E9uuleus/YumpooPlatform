---
title: 领域事件与 Outbox
description: 事件与业务数据同事务写入，分发器认领、重试与死信，消费者回执去重，以及事件契约的冻结与退役。
order: 30
audience: [developer]
---

读完你会知道：一个业务变化如何变成动态、收件箱通知和治理记录，失败时怎样重试，以及事件契约怎样演进。

::: diagram outbox-flow
:::

## 同事务追加

业务操作在写入业务表的**同一个数据库事务**里追加 `outbox_event` 记录；追加方法要求调用方已有事务（`MANDATORY`），保证“数据改了但事件丢了”不会发生。

## 分发

[OutboxDispatcher](../../../../../../backend/src/main/java/com/yumpoo/platform/foundation/application/outbox/OutboxDispatcher.java) 定期认领待处理事件：

- 用 `FOR UPDATE SKIP LOCKED` 认领一批事件并写入租约，多个工作者不会抢同一条；
- 同一聚合的事件按版本顺序投递；
- 每个消费者处理成功后写入**消费回执**，重复投递时按回执去重。

## 重试与死信

失败的事件按 [OutboxRetryPolicy](../../../../../../backend/src/main/java/com/yumpoo/platform/foundation/application/outbox/OutboxRetryPolicy.java) 退避重试：**1 分钟、5 分钟、30 分钟、2 小时、8 小时**。五次都失败后进入 `DEAD` 状态并记录错误日志，运维中心的事件队列指标和告警会反映积压。

## 消费者

| 消费者 | 产出 |
|---|---|
| 活动投影（audit） | 项目动态、工作项单元格修改历史 |
| 收件箱投影（notification） | @提及、回复、指派、成员变动等通知 |
| 项目删除通知（notification） | 删除计划、到期提醒、撤销 |
| 工时提醒（notification） | 工时异常提醒 |
| 治理投影（administration） | 负责人缺失等治理问题 |
| 身份事实消费者（identityaccess） | 账号与授权版本的后续处理 |

## 事件契约

- 每个事件类型和版本在 [contracts/events/catalog.yaml](../../../../../../contracts/events/catalog.yaml) 登记 JSON Schema 与正反金样例，CI 校验生产者输出符合 Schema。
- 已发布的事件版本是**冻结**的：字段不能删改，只能发新版本；冻结快照见 [contracts/events/freeze](../../../../../../contracts/events/freeze/workitem-m2-v1.json)。
- 不再产生的事件版本登记在 [退役清单](../../../../../../tools/events/retired-event-contracts.json)，保留 Schema 以便回放历史数据。
