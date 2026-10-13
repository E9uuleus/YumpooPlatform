# Agent Note: 定时任务框架与通讯录定时同步

Status: implemented

## Problem

共享 `@Scheduled` 调度池大小为 1 并承载 Outbox，通讯录长任务会阻塞事件投递。公司管理员需要可持久化的计划、最近一次结果及执行追踪；调度属于横切触发上下文，不能为此新增日志模块，也不能让 foundation 依赖业务模块。

## Decision

foundation 提供 `ScheduledJob` SPI 与调度服务，V73 建立无公司外键的 `scheduled_job`，按公司和任务码保存启用状态、Spring 六段 cron、最近一次结果与配置版本。未保存的任务默认停用，规则为每天 02:00，ETag 为 `"0"`。COMPANY_ADMIN（含 APP_MANAGER）可查看和配置；PUT 复用 XSRF 与 If-Match，未知任务先返回 404，配置冲突返回 412。

保存时快照公司时区并计算 next_run_at；执行前通过预期 next_run_at 做 CAS 认领，再从当前时间计算下一次计划。服务停机后最多补跑一次，不逐个重放错过的时刻。执行更新不改变 row_version，避免后台运行使管理员保存发生无关冲突。独立守护线程 `yumpoo-job-runner` 每 60 秒检查一次，隔离共享 Outbox 调度池。

每次执行创建根 requestId，同时打开 RequestCorrelationContext 和含 requestId、correlationId、job 的 MDC 上下文。结果输出 `scheduler.job.completed`、`scheduler.job.failed` 或 `scheduler.job.skipped`，携带 outcome、errorCode、durationMs；未预期异常记录原因并映射为 FAILED / SCHEDULED_JOB_UNEXPECTED_FAILURE。日志模块仍由 logger 包名派生，沿用[运维中心的诊断边界](2026-09-26-operations-center.md)。定时任务页和同步运行详情只向 APP_MANAGER 展示按 requestId 跳转运行日志的入口。

identityaccess 的 DIRECTORY_SYNC bean 通过 ObjectProvider 取得现有通讯录用例，使用 SYSTEM / DIRECTORY_SYNC_SCHEDULER 和稳定计划时刻 triggerKey。依赖不可用或 disposition 非 NEW 时为 SKIPPED；业务结果 SUCCEEDED、PARTIALLY_SUCCEEDED、FAILED 分别映射为 SUCCEEDED、PARTIAL、FAILED，仍运行中的记录映射为 SKIPPED / DIRECTORY_SYNC_STILL_RUNNING。复用现有身份事件，不增加事件契约。

## Alternatives considered

- 共享 `@Scheduled`：通讯录耗时会阻塞单线程 Outbox，采用独立执行线程。
- Quartz 或 ShedLock：现有单实例需求只需 next_run_at CAS 与持久化配置，不增加调度依赖。
- 通用 scheduled_job_run 历史表：现有任务的业务数据和运行日志足够，不重复保存历史。
- 通过 last_slot_at 迭代补跑：停机恢复可能连续触发大量全量同步，选择最多补跑一次。
- YAML 静态配置：公司管理员无法在页面配置，采用数据库和乐观并发控制。
- 新增日志模块：调度是触发上下文，采用 MDC job 与现有运行日志，保留代码归属模块。

## Consequences

配置只保存最近一次结果，历史留在业务运行表和运行日志；公司时区变更后需重新保存计划才能使用新时区。执行中进程崩溃会留下 lastStartedAt 大于 lastFinishedAt 的运行中状态，直到下次运行覆盖。新增任务需要一个 ScheduledJob bean 和前端任务元数据，不依赖 foundation 反向调用业务模块。活动记录仍由各业务事件负责，调度不产生额外活动投影。

第二个任务 WORK_HOURS_ANOMALY 以计划时刻 triggerKey 推导前一自然日的检查日，口径与去重由[工作日历与工时异常决策](../product/2026-10-12-work-calendar-and-overtime.md)拥有。
