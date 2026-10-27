# Agent Note: 项目结束与重新打开

Status: implemented

## Problem

项目完成后只能归档。归档会对成员隐藏项目、退出统计，不符合“项目已完成但仍需查看和统计”的场景。

## Decision

1. 项目生命周期保持 `ACTIVE/ARCHIVED`；新增可空 `ended_at`，已结束表示 `ACTIVE` 且结束时间不为空。已结束只影响写入，不影响可见性和统计，连接可见性与报表继续按生命周期判断。
2. 项目内容只读。`ProjectLifecycleService.lockForNewFact` 拒绝已结束项目，返回 `INVALID_STATE_TRANSITION / PROJECT_ENDED`；经由它的 moderation、工作项、讨论、标签、附件、计时与工时补录写入同样拒绝。项目设置、负责人转交与成员管理沿用归档的拒绝规则，能力字段对应关闭。
3. 仅进行中项目可以结束，仅已结束且未归档项目可以重新打开。已结束项目可以归档，从归档恢复后保留结束时间；归档状态下不能结束或重新打开。
4. 负责人或 CompanyAdmin 可以操作。命令要求 XSRF、强 If-Match 和幂等键，锁定项目行，在同一事务内写入安全审计与 Outbox，投影到项目动态和公司活动日志。
5. 结束时在同一事务内调用 `ProjectTimerStopPort.stopRunningTimers`，复用归档锁序；重新打开不停止计时。
6. `findWritableProjects` 与计时器最近、继续列表排除已结束项目。项目列表支持可选的 `ended` 筛选，管理列表仍保留项目。
7. V83 只增加可空列，不登记迁移回退例外。旧版本忽略此列，回退后已结束项目暂时恢复可写是接受的取舍。

[归档决定](2026-10-09-project-archive-and-deletion.md)中的生命周期仍只有 ACTIVE/ARCHIVED 的决定继续成立。本记录部分替代[公司项目总览](2026-10-11-company-project-overview.md)中“进行中 / 已归档”的标签页命名，改为“未归档 / 已归档”，未归档中可筛选进行中和已结束。

## Alternatives considered

- 新增 `ENDED` 枚举值：约 20 处 `== ACTIVE` 的可见性与统计判断会排除已结束项目，约 15 处 `== ARCHIVED` 的写入守卫会放行它，还需要修改 CHECK 约束，回退不安全。
- 复用归档：与项目继续可见并计入统计的要求冲突。
- 只加状态标签、内容仍可编辑：用户已经否决，无法表达完成后的只读状态。

## Consequences

新增事实写入必须经由 `lockForNewFact`；能力判断使用 `ProjectAccessSnapshot.contentWritable()`。报表与可见性继续按 `lifecycle` 判断；已结束项目可以归档，恢复后保持已结束。个人配置沿用原有项目存续锁，不受内容只读影响。连接的可见性仍按生命周期判断；新增/解除连接与连接列管理能力按项目是否可写判断，内部连接目标快照携带结束标记，仅用于写能力，不隐藏既有连接或限制另一进行中项目自身的字段编辑。

领域测试验证结束后归档和恢复仍保留结束时间及重复结束拒绝；治理测试验证结束停止计时并追加对应事件、重新打开不停止计时；HTTP 集成用例验证成员仍可读、写入拒绝、重新打开可写及动态文案。
