# Agent Note: Identity 事实事件确认与缺失消费者恢复

Status: implemented

## Problem

身份模块发布的登录、目录同步、会话撤销及角色变更事实已经完成业务写入和所需安全审计，但缺少 Outbox 消费者。Dispatcher 将其标记为 `DEAD / NO_MATCHING_CONSUMER`；同一聚合更高版本的事件因此无法领取，User 聚合上的撤销事件甚至会挡住已有的账号、就业与负责人治理投影。只注册消费者不能恢复已经 DEAD 的事件。

## Decision

[IdentityCommittedFactConsumer](../../../../backend/src/main/java/com/yumpoo/platform/identityaccess/application/event/IdentityCommittedFactConsumer.java) 使用显式类型和版本订阅，只确认以下 10 个合同组合：

- `identity.directory_sync_started@1`、`identity.directory_sync_completed@1/@2`、`identity.directory_sync_failed@1`。
- `identity.login_succeeded@1`、`identity.login_rejected@1`。
- `identity.user_sessions_revoked@1/@2`。
- `identity.platform_role_granted@1`、`identity.platform_role_revoked@1`。

这些事实不需要再执行异步业务动作；消费者依靠现有 `OutboxConsumerExecutor` 在消费事务中保存 `identity-committed-facts-v1` 回执，保留去重和多消费者投递语义。这里不重复执行登录、会话撤销、角色变更或 Security Audit，也不宣称执行了 JSON Schema 载荷校验。`directory_sync_completed@1` 虽不再由当前代码发布，仍按历史合同完成确认；两版 `user_sessions_revoked` 都仍在发布。

账号启停、离职返岗及 AppManager 可用性六类事件继续由原治理消费者维护 Product、Project 和 Company 的治理问题。它们不加入事实确认消费者，防止未来真实治理消费者缺失被空确认掩盖。Dispatcher 对未知类型、未知版本和真实消费者异常的 DEAD、重试及顺序规则保持原义。

[IdentityOutboxRecoveryRunner](../../../../backend/src/main/java/com/yumpoo/platform/identityaccess/infrastructure/event/IdentityOutboxRecoveryRunner.java) 每次启动针对上述确切订阅调用 foundation 的恢复端口；SQL 由 [JdbcOutboxRepository](../../../../backend/src/main/java/com/yumpoo/platform/foundation/infrastructure/outbox/JdbcOutboxRepository.java) 持有。恢复同时要求原状态为 `DEAD`，错误三元组为 `outbox.dispatcher / NO_MATCHING_CONSUMER / ConsumerRegistryFailure`，将其置为可再次领取的 `RETRY`。不直接标记完成，不改动事件事实、尝试次数、原错误字段或已有回执；完成后仍由正常 Dispatcher 清除错误并释放同聚合后续版本。

恢复按每个类型和版本使用独立事务，SQL 状态条件保证重复启动及同版本多实例重复执行幂等。中途数据库失败会使启动失败，已提交的恢复在下次启动可继续；不在每次轮询中扫描历史事件。禁用 Outbox 调度时仍可以重排，但不会主动派发。恢复 runner 以最高优先级在首次身份引导和角色维护 runner 之前执行，因为这些维护命令会调用 `SpringApplication.exit` 关闭应用；不能在它们返回后访问已关闭的数据源。该修复不新增数据库结构或 Flyway 版本。

## Alternatives considered

- 对所有无消费者事件或 `identity.*` 通配完成：会把漏接的新事件、未知版本和未来真实下游需求静默丢弃。
- 让 DEAD 不再阻塞后续版本：破坏既有聚合有序投递约束，真实消费失败也会被绕过。
- 重新实现一套异步安全审计：所需审计已在原业务事务完成，会复制事实与幂等规则。
- 只新增消费者或一次性人工 SQL：前者不能解除历史堵塞，后者要求每个环境手动操作且容易扩大恢复范围。
- 通过 Flyway 数据迁移直接完成事件：完成状态应由 Dispatcher 和消费回执决定；启动恢复无需占用数据库结构迁移序号，独立修复也不影响并行功能的版本顺序。

## Consequences

新增身份事件或版本必须明确选择真实下游消费者或经审查的事实确认订阅，不能按模块名称自动放行。将来给已确认事件增加新投影时，历史 COMPLETED 不会自动回放，需要另定历史边界；本修复不改变 [Activity 历史切点](2026-08-30-activity-projection-contract.md)。

升级遵循现有 [Windows RUNBOOK](../../../../deployment/windows/RUNBOOK.md) 的停旧进程再启动新版本流程。恢复不保证新旧 worker 同时运行的滚动升级：旧 worker 可以在启动扫描之后再次制造缺失消费者 DEAD。若发生这种混合部署，应停止旧 worker 后重启新版本以重新执行限定恢复；不能清空所有 DEAD。回退旧程序不需要回退 schema，但旧程序仍会让新产生的这些事件失败。

[IdentityOutboxIT](../../../../backend/src/test/java/com/yumpoo/platform/identityaccess/infrastructure/event/IdentityOutboxIT.java) 用合同样例核对身份订阅覆盖，并验证历史版本回执、重排幂等、错误边界、现有租约与完成状态不变，以及撤销事件恢复后真实负责人治理投影继续执行。[IdentityOutboxRecoveryRunnerTest](../../../../backend/src/test/java/com/yumpoo/platform/identityaccess/infrastructure/event/IdentityOutboxRecoveryRunnerTest.java) 用真实 SpringApplication 和角色维护 runner 验证恢复先于维护与关停完成。
