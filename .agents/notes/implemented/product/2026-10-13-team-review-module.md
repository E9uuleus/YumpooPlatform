# Agent Note: 团队审阅模块、我的工时入口与分角色首页

Status: implemented

## Problem

仪表板标签栏混放了私人画布、本人工时和管理员报表，职责不清；首页对所有角色相同，缺少管理者常用的业务审阅与运维入口。

## Decision

- 工作台只放个人范围；一级模块「团队」是公司管理人员的业务审阅视角，仅 COMPANY_ADMIN 可见，APP_MANAGER 自动包含。公司管理只做配置与治理，先例是独立的运维中心。
- 团队路由为 `team/:view(timesheet|overtime|workload)`，侧栏切换成员工时、加班统计和成员当前任务，共用组件保留周期和勾选状态。我的工时使用 `workspace/:workspaceSlug/my-time`，入口在工作台侧栏的仪表板之后。
- 旧的工作台团队地址和 `/dashboards/team` 提醒入口由会话守卫转到 `/team/timesheet`，保留查询参数和 hash；旧的 `my-time` 固定标签地址转到我的工时页。普通成员访问团队地址进入 forbidden。
- 仪表板只剩私人画布，保留原有选择、重命名、保存和导出能力。
- 首页卡片按 `memberTier` 增减：普通成员保留原卡片，仅工时日历「查看明细」改到新页面；公司管理员第二张卡片为团队视图；APP_MANAGER 另有运维卡片与告警数。
- 引导只调整模块、工作台导航和仪表板标签文案，不提升版本。

## Alternatives considered

- 团队视图放工作台：违背[公司项目总览](2026-10-11-company-project-overview.md)的个人与公司管理分开原则。
- 团队视图放公司管理：业务审阅混入配置与治理。
- 三套独立首页：增加维护和引导成本，单页按角色增减卡片即可满足需要。

## Consequences

管理员模块栏多出「团队」。localStorage 中记住的 `my-time` 或 `team` 被忽略，回落到第一个私人仪表板。团队模块与我的工时页复用仪表板全屏画布外壳的样式。

本记录部分替代[仪表板标签栏、PDF 导出与团队视图](2026-10-08-dashboard-tabs-export-team-view.md)的团队固定标签与分段切换入口、[工作中台首页与本人工时日历](2026-10-10-work-hub-and-my-time.md)的我的工时固定标签入口，以及[工作日历、加班统计与工时异常提醒](2026-10-12-work-calendar-and-overtime.md)的提醒入口归一化目标；统计口径、权限和导出决策继续有效。
