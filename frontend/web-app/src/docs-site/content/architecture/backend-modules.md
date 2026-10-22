---
title: 后端模块化单体
description: 12 个业务模块、四层结构、跨模块只调 api 层，以及允许的依赖方向。
order: 20
audience: [developer]
---

读完你会知道：后端有哪些模块、每个模块内部怎么分层、模块之间允许怎样依赖，以及为什么选择模块化单体。

::: diagram backend-modules
:::

## 模块

| 模块 | 职责 |
|---|---|
| `foundation` | 共享基础：错误模型、分页、HTTP 约定、Outbox 事件分发、定时任务框架、请求关联 |
| `organization` | 公司与组织信息、时区 |
| `identityaccess` | 企业微信登录、会话、CSRF、成员账号、平台角色、通讯录同步 |
| `catalog` | 项目、项目成员、工作项类别与标签目录、项目生命周期 |
| `filestorage` | 附件上传、类型检测、内容寻址存储 |
| `workitem` | 工作项、讨论、连接、计时与工时会话 |
| `worklog` | 工时提交与审批的预留模块，目前只有层标记 |
| `notification` | 收件箱投影与通知偏好 |
| `audit` | 活动记录与安全审计 |
| `reporting` | 仪表板、项目统计、团队视图、工作日历 |
| `administration` | 管理编排：治理、跨模块的管理操作 |
| `operations` | 运维中心：指标采样、运行日志、告警 |

## 四层结构

每个模块按四层组织，层次用 [LayerMarker](../../../../../../backend/src/main/java/com/yumpoo/platform/foundation/api/LayerMarker.java) 标注：

| 层 | 可以依赖（同模块内） |
|---|---|
| `api` | `api`、`application` |
| `application` | `application`、`domain` |
| `domain` | 只有 `domain` |
| `infrastructure` | `infrastructure`、`application`、`domain` |

**跨模块只能调用对方的 `api` 层**，不能触碰对方的应用服务、领域对象或仓储。

## 允许的模块依赖

以下矩阵逐字取自 [ArchitectureRules.java](../../../../../../backend/src/test/java/com/yumpoo/platform/architecture/ArchitectureRules.java)，ArchUnit 测试据此检查，违反即失败：

| 模块 | 可以依赖 |
|---|---|
| `foundation` | （无） |
| `organization` | foundation |
| `identityaccess` | foundation、organization、audit |
| `catalog` | foundation、identityaccess |
| `filestorage` | foundation |
| `workitem` | foundation、catalog、identityaccess、filestorage、audit |
| `worklog` | foundation、organization、catalog、workitem、identityaccess |
| `notification` | foundation、identityaccess |
| `audit` | foundation |
| `reporting` | foundation、organization、identityaccess、catalog、filestorage、workitem、worklog、notification、audit |
| `administration` | foundation、organization、identityaccess、catalog、filestorage、workitem、worklog、notification、audit、reporting |
| `operations` | foundation、identityaccess、audit |

## 为什么是模块化单体

- 团队规模和部署环境（单台 Windows 主机）不需要分布式系统的运维成本；一个 JAR、一个数据库，部署与回退都是原子的。
- 模块边界由测试强制，而不是靠网络隔离，所以拆分的成本低：将来真有需要时，按 `api` 层切开即可。
- 跨模块的副作用经领域事件与 Outbox 异步传递，见 [领域事件与 Outbox](events-and-outbox.md)，这让模块间的耦合与微服务相当。
