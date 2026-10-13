# Agent Note: 项目固定统计

Status: implemented

## Problem

私人仪表板只统计本人所在的项目，不能共享；项目缺少开箱即用的统计，成员与管理者无法从项目直接查看一致的进度和投入视角。

## Decision

- 项目统计使用固定模板，组件定义在前端，任何人都不能编辑模板；服务端强制范围为路径项目。展开视图复用工作项表格，可按原工作项权限修改数据。
- 可见性走 `ProjectAccessSnapshotQuery`：项目负责人、成员和未加入项目的公司管理员可读，后者只读。归档或不可见项目返回 404。
- workitem 提供项目作用域入口 `projectCharts`、`projectChartItems`、`projectTable` 和 `projectTimeEntries`；共享表格请求中的 `projectId` 不覆盖路径项目。
- 全局筛选 `includeArchived=true`；计数类组件在组件级排除已归档工作项；累计耗时包含已归档工作项。逾期为 TODO 或 IN_PROGRESS 且截止日期不晚于公司时区的昨天。
- 周工作项按成员和工作项汇总，按公司时区切分自然日，运行中计时统计到 `asOf`，区间最多 93 天。`projectMember` 标识成员当前是否仍在项目内，已离开者保留工时并显示标签。
- [私人仪表板](2026-09-16-personal-dashboards.md)的 `MemberProjectQuery` 成员边界保持不变；项目统计不需要私人仪表板行或共享配置。

## Alternatives considered

- 共享可编辑的项目仪表板：需要共享配置与并发保存，本期不做。
- 复用 `/me/dashboards`：依赖私人仪表板行和成员边界，不适合项目公共统计。
- 周工作项逐日矩阵：偏考勤化，按成员和工作项汇总更适合核对项目投入。

## Consequences

项目成员可以看到他人在本项目上的周工时汇总，底层计时记录原本可见。公司管理员可以只读查看任意进行中项目的统计，工作项写权限仍由既有接口判断。
