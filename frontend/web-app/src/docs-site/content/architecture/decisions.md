---
title: 决策记录
description: Agent Notes 记录长期决策与理由；按类别列出关键的已实施决策。
order: 100
audience: [developer]
---

读完你会知道：仓库里的长期决策记录在哪里、什么时候要写，以及有哪些关键决策值得先读。

## Agent Notes

`.agents/notes/` 记录**长期且将来可能被重新讨论**的决策：问题、决策、考虑过的替代方案和后果。改动建立、修改或推翻这类决策时，需要在同一个 PR 新增或更新 Note；CI 只校验已有 Note 的结构，是否需要写由作者和评审判断。

- `implemented/`：当前有效的决策，是实现的权威依据。
- `archived/`：冻结的历史，不得修改，也不能作为当前实现的依据（本页不收录）。

写作规范见 [Agent Notes 治理](../../../../../../.agents/notes/implemented/process/2026-08-20-agent-notes-governance.md)。

## 架构

| 决策 | 一句话 |
|---|---|
| [工作项核心契约](../../../../../../.agents/notes/implemented/architecture/2026-08-22-work-item-core-contract.md) | 工作项的字段、并发与幂等约定 |
| [活动投影契约](../../../../../../.agents/notes/implemented/architecture/2026-08-30-activity-projection-contract.md) | 动态由事件投影生成，而不是业务代码直接写 |
| [附件上传处理](../../../../../../.agents/notes/implemented/architecture/2026-08-25-attachment-upload-processing.md) | 类型检测、内容寻址与原子发布 |
| [工作项计时](../../../../../../.agents/notes/implemented/architecture/2026-09-07-work-item-time-tracking.md) | 计时会话的模型与统计口径 |
| [并行计时](../../../../../../.agents/notes/implemented/architecture/2026-10-14-parallel-time-tracking.md) | 多项同时计时按项数平均计入 |
| [收件箱](../../../../../../.agents/notes/implemented/architecture/2026-09-25-notification-inbox.md) | 通知由事件投影到收件箱 |
| [运维中心](../../../../../../.agents/notes/implemented/architecture/2026-09-26-operations-center.md) | 指标采样、运行日志与告警的边界 |
| [错误分类与诊断](../../../../../../.agents/notes/implemented/architecture/2026-10-30-error-classification-and-diagnostics.md) | 输入问题与内部错误分开记录，提示可复制诊断信息 |
| [定时任务](../../../../../../.agents/notes/implemented/architecture/2026-10-12-scheduled-jobs.md) | 独立调度线程与可配置计划 |
| [工作项连接](../../../../../../.agents/notes/implemented/architecture/2026-09-30-work-item-connections.md) | 跨项目双向连接 |

## 数据

| 决策 | 一句话 |
|---|---|
| [迁移回退安全](../../../../../../.agents/notes/implemented/data/2026-10-15-rollback-safe-migrations.md) | 迁移默认只扩展，破坏性变更拆两次发布 |
| [工作项事件契约冻结](../../../../../../.agents/notes/implemented/data/2026-08-31-work-item-event-contract-freeze.md) | 已发布事件版本不可修改 |
| [多处理人](../../../../../../.agents/notes/implemented/data/2026-10-05-work-item-multiple-assignees.md) | 一项最多 20 位处理人 |
| [父子关系](../../../../../../.agents/notes/implemented/data/2026-08-28-work-item-parent-child-relations.md) | 只支持一级子项 |

## 安全

| 决策 | 一句话 |
|---|---|
| [三级平台角色](../../../../../../.agents/notes/implemented/security/2026-09-25-platform-role-tiers.md) | 平台管理员 ⊇ 公司管理员 ⊇ 普通成员 |
| [会话保持与桌面防护](../../../../../../.agents/notes/implemented/security/2026-10-19-session-remember-and-desktop-guards.md) | 7 天内免登录与桌面权限守卫 |
| [CSRF 收敛修复](../../../../../../.agents/notes/implemented/security/2026-08-22-convergent-csrf-repair.md) | 并发请求收敛到同一个修复令牌 |
| [本地登录夹具](../../../../../../.agents/notes/implemented/security/2026-08-21-local-authentication-fixture.md) | 只允许回环地址的开发登录 |
| [通讯录停用账号](../../../../../../.agents/notes/implemented/security/2026-10-12-directory-account-disable.md) | 离职成员同步时自动停用 |

## 产品

| 决策 | 一句话 |
|---|---|
| [统一项目模型](../../../../../../.agents/notes/implemented/product/2026-09-30-unified-project-model.md) | 去掉产品层，只保留项目 |
| [项目归档与删除](../../../../../../.agents/notes/implemented/product/2026-10-09-project-archive-and-deletion.md) | 归档可恢复，永久删除有 30 天缓冲期 |
| [个人表格设置](../../../../../../.agents/notes/implemented/product/2026-10-07-work-item-table-settings.md) | 筛选、排序、分组等只影响自己 |
| [隐藏已完成](../../../../../../.agents/notes/implemented/product/2026-10-19-work-item-hide-completed.md) | 按本人定义置灰、加删除线或不显示已完成工作项 |
| [我的一天与关闭计时器](../../../../../../.agents/notes/implemented/product/2026-10-15-my-day-timeline-and-timer-opt-out.md) | 时间线补录，可以只手动填写 |
| [工作日历与加班](../../../../../../.agents/notes/implemented/product/2026-10-12-work-calendar-and-overtime.md) | 标准工时与加班由公司日历决定 |
| [新手引导](../../../../../../.agents/notes/implemented/product/2026-10-11-new-user-onboarding.md) | 页面导览的触发与版本 |
| [快捷工时与偏好设置](../../../../../../.agents/notes/implemented/product/2026-10-19-desktop-quick-time-and-preferences.md) | 托盘小窗按时长补录 |
| [Canvas 氛围动效](../../../../../../.agents/notes/implemented/product/2026-09-30-canvas-ambient-motion.md) | 动效只用在非工作界面 |
| [已归档工作项](../../../../../../.agents/notes/implemented/product/2026-10-30-archived-work-items.md) | 工具栏抽屉查看与恢复归档的工作项 |

## 流程

| 决策 | 一句话 |
|---|---|
| [main 发布线](../../../../../../.agents/notes/implemented/process/2026-10-15-main-release-line.md) | 草稿 Release，Publish 即上线 |
| [PR 发布说明](../../../../../../.agents/notes/implemented/process/2026-10-20-release-notes-from-pull-requests.md) | Release 描述由 PR 的「## 发布说明」生成 |
| [发布版本标识](../../../../../../.agents/notes/implemented/process/2026-10-15-release-version-identity.md) | 版本以 Release tag 为准 |
| [OpenAPI 分域源](../../../../../../.agents/notes/implemented/process/2026-10-21-openapi-split-sources.md) | 分域编辑、单文件生成 |
| [桌面发布渠道](../../../../../../.agents/notes/implemented/process/2026-10-17-desktop-release-channel.md) | 安装包、签名更新与下载页 |
| [本地浏览器冒烟](../../../../../../.agents/notes/implemented/testing/2026-10-21-local-e2e-smoke.md) | 只在本地运行的串行冒烟 |
