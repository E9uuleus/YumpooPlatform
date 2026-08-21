# 一期需求追踪矩阵

- 状态：Confirmed baseline
- 目标阶段：M0 建立，M1–M6 持续更新
- 适用范围：一期稳定需求 ID、主交付阶段、权威需求文件、设计实现和验收证据
- 依赖：[阶段路线图](./00-roadmap-and-dependencies.md)、`docs/10-modules/*/01-requirements.md`

## 1. 使用规则

1. 各模块 `01-requirements.md` 是编号和业务语义的唯一真源；阶段、架构和实现文档只引用，不另建同前缀摘要 ID。
2. 本矩阵把主阶段、设计实现和验收证据相同的连续 ID 合并为范围行。范围内任一需求状态不同时，必须拆成单独行。
3. ID 发布后不得复用或静默改变语义；废止时保留并标记 `Superseded`，同时链接替代决策。
4. 基线状态为 `Confirmed`；完成编码和迁移后为 `Implemented`；验收证据通过后为 `Verified`。
5. API 和 OPS 条目由本矩阵及对应横切/运维文档管理，不占用业务模块前缀。

## 2. 身份、组织与授权

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| IAM-001 | M1 | [身份需求](../10-modules/01-identity-and-organization/01-requirements.md) | [身份数据设计](../10-modules/01-identity-and-organization/02-domain-and-data-design.md) | 单 Company 归属与隔离测试 | Confirmed |
| IAM-002, IAM-003, IAM-004, IAM-005, IAM-006 | M1 | [身份需求](../10-modules/01-identity-and-organization/01-requirements.md) | [身份 API/验收](../10-modules/01-identity-and-organization/03-api-implementation-and-acceptance.md) | SSO、稳定 ID、双状态、离职撤销场景 | Confirmed |
| IAM-007, IAM-008 | M1 | [身份需求](../10-modules/01-identity-and-organization/01-requirements.md) | [身份数据设计](../10-modules/01-identity-and-organization/02-domain-and-data-design.md) | 同步成功、部分成功、失败与重试 | Confirmed |
| IAM-009, IAM-010, IAM-011, IAM-012 | M1 | [身份需求](../10-modules/01-identity-and-organization/01-requirements.md) | [身份 API/验收](../10-modules/01-identity-and-organization/03-api-implementation-and-acceptance.md) | 时区/周、成员分页、快照、Secret 脱敏 | Confirmed |
| IAM-013, IAM-014 | M1 | [身份需求](../10-modules/01-identity-and-organization/01-requirements.md) | [身份 API/验收](../10-modules/01-identity-and-organization/03-api-implementation-and-acceptance.md) | 状态事件与返聘不覆盖手工禁用 | Confirmed |
| ACL-001, ACL-009, ACL-010, ACL-011, ACL-013, ACL-014 | M1（资源级回归 M2） | [授权需求](../10-modules/02-roles-and-project-authorization/01-requirements.md) | [安全授权](../02-architecture/06-security-and-authorization-model.md)、[M0-02](./M0-02-module-ownership-and-orchestration.md) | 企业/平台角色、APP_MANAGER、会话撤权和授权端口契约；真实 Project 资源在 M2 回归 | Confirmed |
| ACL-002, ACL-003, ACL-004, ACL-005, ACL-006, ACL-007, ACL-008, ACL-012 | M2 | [授权需求](../10-modules/02-roles-and-project-authorization/01-requirements.md) | [授权数据设计](../10-modules/02-roles-and-project-authorization/02-domain-and-data-design.md)、[M0-02](./M0-02-module-ownership-and-orchestration.md)；M2-03 已验证 ACL-002/004/012 的 Product 唯一负责人、管理员治理与 OWNER_MISSING 切片，见 [证据](../../evidence/m2-03/acceptance-matrix.json) 与 [决策](../../.agents/notes/implemented/product/2026-08-20-product-lifecycle-contract.md) | Project owner/membership、管理员项目只读与完整项目 ACL 仍在后续 M2 切片 | Confirmed |

## 3. Project、Content 与协作事实

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| PPM-001（Workspace 多实例部分） | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | V17 Workspace 数据模型、生命周期 API、OpenAPI/TS 客户端与 v1 事件契约；Project 归属和迁移留给 M2-04/M2-08 | [M2-02 验收证据](../../evidence/m2-02)已覆盖 Workspace 创建、查询、更新、归档恢复与备份恢复 | Verified |
| PPM-001（Project 归属/迁移部分）, PPM-002, PPM-003, PPM-004 | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | M2-06 已验证 Project 权限查询；M2-07 已验证有效 Product 关系筛选；M2-08 已交付 DRAFT/ACTIVE Project 向同 Company ACTIVE Workspace 的受锁迁移 | M2-08 证据只覆盖当前真实生命周期与迁移能力 | Verified |
| PPM-005 | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | [固定模板 ADR](../90-decisions/ADR-002-fixed-templates.md)、V16 `templateworkflow` 迁移与公开查询/治理端口 | 四类固定模板、状态/迁移目录、不可变触发器、发布/停用事务及 `verify:m2-01` | Verified |
| PPM-006 | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | [固定模板 ADR](../90-decisions/ADR-002-fixed-templates.md)；M2-04 已交付 PUBLISHED 模板共享锁、Project/ACTIVE owner membership/全部 blueprint Content 原子初始化与 provenance | [M2-04 验收证据](../../evidence/m2-04)覆盖四类映射、并发、故障回滚和备份恢复 | Verified |
| ACL-002、ACL-004、ACL-006、ACL-011～ACL-013 | M2 | [角色与项目授权](../10-modules/02-roles-and-project-authorization/01-requirements.md) | M2-05 已交付 Project membership 唯一真源、Owner/管理员成员治理、原子重指派及 PROJECT OWNER_MISSING 投影 | [M2-05 验收证据](../../evidence/m2-05)覆盖权限、状态、ETag、幂等、并发、回滚、治理与备份恢复 | Verified |
| PPM-007, PPM-008, PPM-009, PPM-010 | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | M2-06 已验证客户字段；M2-07 已交付四类 Product–Project 关系、唯一主关系、权限和软移除 | PPM-AT-010 的真实 Feedback 引用解绑 blocker 留给 M3B/M2-24 | Confirmed |
| PPM-011, PPM-012, PPM-013, PPM-014, PPM-015, PPM-016 | M2 | [项目需求](../10-modules/03-workspace-product-project/01-requirements.md) | M2-06 已交付 DRAFT→ACTIVE；M2-08 已交付普通/覆盖归档、恢复、Workspace 占用守卫与 blocker 协议 | PPM-014 的 Work Item、Worklog、Feedback 三类真实 provider 和全量业务验收明确留给 M2-24，不在 M2-08 标记 Verified | Confirmed |
| WIT-001, WIT-002, WIT-003, WIT-004 | M2 | [工作项需求](../10-modules/04-content-and-work-items/01-requirements.md) | [工作项数据设计](../10-modules/04-content-and-work-items/02-domain-and-data-design.md) | Content 单类型、TABLE/KANBAN 同源 | Confirmed |
| WIT-005, WIT-006, WIT-007, WIT-008, WIT-009, WIT-010, WIT-011 | M2 | [工作项需求](../10-modules/04-content-and-work-items/01-requirements.md) | [工作项 API/验收](../10-modules/04-content-and-work-items/03-api-implementation-and-acceptance.md) | 字段、自然日、迁移、分页、Kanban rank | Confirmed |
| WIT-012, WIT-013, WIT-014, WIT-015 | M2 | [工作项需求](../10-modules/04-content-and-work-items/01-requirements.md) | [并发规范](../20-cross-cutting/concurrency-and-idempotency.md) | 412、软删除、归档只读、项目权限 | Confirmed |
| UAA-001, UAA-002, UAA-003, UAA-004, UAA-005 | M2 | [协作附件需求](../10-modules/05-updates-attachments-activity/01-requirements.md) | [协作附件设计](../10-modules/05-updates-attachments-activity/02-domain-and-data-design.md) | Update 净化、提及、15 分钟窗口与治理删除 | Confirmed |
| UAA-006, UAA-007, UAA-008, UAA-009, UAA-010 | M2 | [协作附件需求](../10-modules/05-updates-attachments-activity/01-requirements.md) | [文件安全](../20-cross-cutting/file-storage-and-content-security.md) | 父对象、实时权限、100 MB、配额与共享 | Confirmed |
| UAA-011, UAA-012, UAA-013, UAA-014, UAA-015 | M2 | [协作附件需求](../10-modules/05-updates-attachments-activity/01-requirements.md) | [审计规范](../20-cross-cutting/audit-activity-and-system-logs.md) | Activity 追加、跨项目裁剪、分页和脱敏 | Confirmed |
| REL-001, REL-002, REL-003, REL-004 | M2 | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [关系数据设计](../10-modules/06-work-item-relations-and-feedback/02-domain-and-data-design.md) | 类型、方向、唯一性、父子无环 | Confirmed |
| REL-005, REL-006, REL-007, REL-008 | M2 | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [关系 API/验收](../10-modules/06-work-item-relations-and-feedback/03-api-implementation-and-acceptance.md) | 双侧权限、不可见占位和软删除 | Confirmed |

## 4. 工时与审批

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| WLG-REQ-001, WLG-REQ-002, WLG-REQ-003, WLG-REQ-004, WLG-REQ-005, WLG-REQ-006, WLG-REQ-007, WLG-REQ-008 | M3A | [工时需求](../10-modules/07-worklog-and-approval/01-requirements.md) | [工时数据设计](../10-modules/07-worklog-and-approval/02-domain-and-data-design.md) | 填报、0.25 小时、日上限、日期与归档用例 | Confirmed |
| WLG-REQ-009, WLG-REQ-010, WLG-REQ-011, WLG-REQ-012, WLG-REQ-013, WLG-REQ-014, WLG-REQ-015, WLG-REQ-016, WLG-REQ-017 | M3A | [工时需求](../10-modules/07-worklog-and-approval/01-requirements.md) | [ADR-004](../90-decisions/ADR-004-worklog-approval-aggregation.md) | WLG-ACC-001–004、WLG-ACC-010–012 | Confirmed |
| WLG-REQ-018, WLG-REQ-019, WLG-REQ-020, WLG-REQ-021, WLG-REQ-022 | M3A | [工时需求](../10-modules/07-worklog-and-approval/01-requirements.md) | [工时 API/验收](../10-modules/07-worklog-and-approval/03-api-implementation-and-acceptance.md) | WLG-ACC-005–006 | Confirmed |
| WLG-REQ-023, WLG-REQ-024, WLG-REQ-025, WLG-REQ-026, WLG-REQ-027, WLG-REQ-028 | M3A | [工时需求](../10-modules/07-worklog-and-approval/01-requirements.md) | [工时数据设计](../10-modules/07-worklog-and-approval/02-domain-and-data-design.md) | WLG-ACC-007；WLG-ACC-008（WorklogCorrectionGroup PENDING/ACTIVE/FAILED 与原子生效） | Confirmed |
| WLG-REQ-029, WLG-REQ-030, WLG-REQ-031, WLG-REQ-032, WLG-REQ-033 | M3A（M4 最终验证） | [工时需求](../10-modules/07-worklog-and-approval/01-requirements.md) | [时间与指标冻结](./M0-04-time-calendar-and-metrics.md)、[通知需求](../10-modules/08-notifications-and-reminders/01-requirements.md) | M3A 验证资格、截止点、业务键和事件；M4 验证调度、投递、补偿及失败不改事实 | Confirmed |

## 5. Product Feedback

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| PFB-001, PFB-002 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [Feedback ADR](../90-decisions/ADR-003-product-feedback-aggregate.md) | 来源/Product 基数与关系合法性 | Confirmed |
| PFB-003, PFB-004, PFB-005 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [关系/反馈数据设计](../10-modules/06-work-item-relations-and-feedback/02-domain-and-data-design.md) | 共享快照、修订与显式附件 | Confirmed |
| PFB-006, PFB-007, PFB-008, PFB-009, PFB-010 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [关系/反馈 API/验收](../10-modules/06-work-item-relations-and-feedback/03-api-implementation-and-acceptance.md) | 来源/Product Owner、处理项目与 PRIMARY 权限 | Confirmed |
| PFB-011, PFB-012, PFB-013, PFB-014, PFB-015, PFB-016 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [Feedback ADR](../90-decisions/ADR-003-product-feedback-aggregate.md) | 所有终态、延期、撤回、验证失败与重开 | Confirmed |
| PFB-017, PFB-018, PFB-019, PFB-020 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [关系/反馈 API/验收](../10-modules/06-work-item-relations-and-feedback/03-api-implementation-and-acceptance.md) | 共享流、无隐式联动、管理员覆盖与归档收尾 | Confirmed |
| PFB-021 | M3B | [关系/反馈需求](../10-modules/06-work-item-relations-and-feedback/01-requirements.md) | [事件模型](../02-architecture/03-domain-event-model.md) | 所有业务时间点可复算 | Confirmed |

## 6. 通知与 Electron

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| NTF-REQ-001, NTF-REQ-002, NTF-REQ-003, NTF-REQ-004, NTF-REQ-005, NTF-REQ-006, NTF-REQ-007 | M4 | [通知需求](../10-modules/08-notifications-and-reminders/01-requirements.md) | [通知数据设计](../10-modules/08-notifications-and-reminders/02-domain-and-data-design.md) | NTF-ACC-001、002、004、007、010 | Confirmed |
| NTF-REQ-008, NTF-REQ-009, NTF-REQ-010, NTF-REQ-011, NTF-REQ-012 | M4 | [通知需求](../10-modules/08-notifications-and-reminders/01-requirements.md) | [通知 API/验收](../10-modules/08-notifications-and-reminders/03-api-implementation-and-acceptance.md) | NTF-ACC-003、005、006、009 | Confirmed |
| NTF-REQ-013, NTF-REQ-014, NTF-REQ-015, NTF-REQ-016, NTF-REQ-017 | M4 | [通知需求](../10-modules/08-notifications-and-reminders/01-requirements.md) | [通知 API/验收](../10-modules/08-notifications-and-reminders/03-api-implementation-and-acceptance.md) | NTF-ACC-008 及故障注入 | Confirmed |
| NTF-REQ-018 | M4（规则在 M3A） | [通知需求](../10-modules/08-notifications-and-reminders/01-requirements.md) | [时间、日历与指标冻结](./M0-04-time-calendar-and-metrics.md)、[调度日历](../20-cross-cutting/scheduling-timezone-and-work-calendar.md) | M004-AT-001～003、NTF 调度时区/日历与固化截止测试 | Confirmed |
| ELC-REQ-001, ELC-REQ-002, ELC-REQ-003, ELC-REQ-004, ELC-REQ-005, ELC-REQ-006, ELC-REQ-007, ELC-REQ-008, ELC-REQ-009, ELC-REQ-010 | M4 | [Electron 需求](../10-modules/09-electron-client/01-requirements.md) | [Electron 数据/边界](../10-modules/09-electron-client/02-domain-and-data-design.md) | ELC-ACC-001–002、005–010 | Confirmed |
| ELC-REQ-011, ELC-REQ-012, ELC-REQ-013, ELC-REQ-014, ELC-REQ-015 | M4 | [Electron 需求](../10-modules/09-electron-client/01-requirements.md) | [ADR-005](../90-decisions/ADR-005-electron-pilot-distribution.md) | ELC-ACC-011–012 | Confirmed |
| ELC-REQ-016, ELC-REQ-017, ELC-REQ-018, ELC-REQ-019, ELC-REQ-020, ELC-REQ-021, ELC-REQ-022 | M4 | [Electron 需求](../10-modules/09-electron-client/01-requirements.md) | [Electron API/验收](../10-modules/09-electron-client/03-api-implementation-and-acceptance.md) | ELC-ACC-003–006 及日志扫描 | Confirmed |

## 7. 统计、管理与运维

| 精确 ID/范围 | 主阶段 | 权威需求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| RPT-REQ-001, RPT-REQ-002, RPT-REQ-003, RPT-REQ-004, RPT-REQ-005 | M5 | [统计需求](../10-modules/10-statistics/01-requirements.md) | [统计数据设计](../10-modules/10-statistics/02-domain-and-data-design.md) | RPT-ACC-001–010 | Confirmed |
| RPT-REQ-006, RPT-REQ-007 | M5 | [统计需求](../10-modules/10-statistics/01-requirements.md) | [时间、日历与指标冻结](./M0-04-time-calendar-and-metrics.md)、[统计 API/验收](../10-modules/10-statistics/03-api-implementation-and-acceptance.md) | RPT-ACC-011～013：日期、空集、四状态、指标版本与时区响应 | Confirmed |
| ADM-REQ-001, ADM-REQ-002, ADM-REQ-003, ADM-REQ-004, ADM-REQ-005 | M5 | [管理需求](../10-modules/11-admin-console/01-requirements.md) | [管理数据设计](../10-modules/11-admin-console/02-domain-and-data-design.md) | ADM-ACC-001–004、007、010 | Confirmed |
| ADM-REQ-006, ADM-REQ-007, ADM-REQ-008, ADM-REQ-009, ADM-REQ-010 | M5 | [管理需求](../10-modules/11-admin-console/01-requirements.md) | [管理 API/验收](../10-modules/11-admin-console/03-api-implementation-and-acceptance.md) | ADM-ACC-005–009 | Confirmed |
| ADM-REQ-011, ADM-REQ-012, ADM-REQ-013, ADM-REQ-014, ADM-REQ-015 | M5 | [管理需求](../10-modules/11-admin-console/01-requirements.md) | [管理 API/验收](../10-modules/11-admin-console/03-api-implementation-and-acceptance.md) | ADM-ACC-002–004 及治理审计 | Confirmed |
| OPS-001 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [Windows 部署](../30-operations/windows-server-deployment.md) | 空白环境部署、重启、健康检查 | Confirmed |
| OPS-002, OPS-003, OPS-004, OPS-005 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [监控日志](../30-operations/monitoring-logging-and-alerting.md) | 管理入口、日志分层、关联与受控重试 | Confirmed |
| OPS-006 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [备份恢复](../30-operations/backup-restore-and-disaster-recovery.md) | 失败/缺失告警与隔离恢复 | Confirmed |
| OPS-007 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [容量测试](../40-quality/performance-and-capacity-test.md) | 约 30 人目标负载报告 | Confirmed |
| OPS-008 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [配置与密钥](../30-operations/configuration-and-secrets.md) | 仓库/日志扫描与轮换演练 | Confirmed |
| OPS-009 | M5 | [M5 运维要求](./M5-admin-reporting-operations.md) | [运行手册](../30-operations/operational-runbooks.md) | 升级、迁移与应用回退演练 | Confirmed |

## 8. API 与并发契约

| 精确 ID | 主阶段 | 权威要求 | 设计/实现 | 验收证据 | 基线状态 |
| --- | --- | --- | --- | --- | --- |
| API-001 | M1 | `/api/v1`、OpenAPI 唯一契约、大版本内兼容新增 | [API 规范](../02-architecture/05-api-and-openapi-conventions.md) | 契约生成与破坏性变更门禁 | Confirmed |
| API-002 | M1 | 分页硬上限、统一错误体、requestId、fieldErrors 与 retryable | [API 规范](../02-architecture/05-api-and-openapi-conventions.md) | 错误码、分页、自然日契约测试 | Confirmed |
| API-003 | M1 | 客户端类型/版本随请求上报并执行最低兼容校验 | [API 规范](../02-architecture/05-api-and-openapi-conventions.md) | Web/Electron/426 测试 | Confirmed |
| API-004 | M1 | 聚合写使用 ETag/If-Match；副作用命令使用幂等键和请求摘要 | [并发与幂等](../20-cross-cutting/concurrency-and-idempotency.md) | 412、428、重放和键复用冲突 | Confirmed |

## 9. M0 决策门禁

| 步骤 | 设计索引 | 决策记录 | 完成证据 | 状态 |
| --- | --- | --- | --- | --- |
| M0-01 | [差异登记](./M0-01-requirement-design-gap-register.md) | GAP-001～GAP-016 | 全部差异 CLOSED，主阶段和回写位置明确 | Confirmed |
| M0-02 | [模块归属与编排](./M0-02-module-ownership-and-orchestration.md) | [ADR-006](../90-decisions/ADR-006-module-ownership-and-orchestration.md) | 表归属、公开端口、创建/归档事务唯一 | Confirmed |
| M0-03 | [API、并发与认证](./M0-03-api-concurrency-and-auth-semantics.md) | [ADR-007](../90-decisions/ADR-007-api-concurrency-and-auth-semantics.md) | If-Match、401/403/404、缺失命令和日期契约唯一 | Confirmed |
| M0-04 | [时间、日历与指标](./M0-04-time-calendar-and-metrics.md) | [ADR-008](../90-decisions/ADR-008-time-calendar-and-metrics.md) | 时区、提醒、截止、状态类别和指标样例唯一 | Confirmed |
| M0-05 | [Activity、Update 与附件](./M0-05-activity-feedback-update-and-attachments.md) | [ADR-009](../90-decisions/ADR-009-activity-feedback-update-and-attachments.md) | 三侧投影、提及/治理和附件状态机唯一 | Confirmed |
| M0-06 | [运维、Electron 与容量](./M0-06-operations-electron-and-performance.md) | [ADR-010](../90-decisions/ADR-010-operations-electron-and-performance.md) | 备份、日志主体、远程 SPA、SSE 和压测口径唯一 | Confirmed |

## 10. 矩阵验收与退出条件

- M0 退出时所有范围行至少为 `Confirmed`，权威需求和主设计文档均存在。
- 各阶段退出时，其范围内每一个实际 ID 均达到 `Verified`；若只完成部分，必须拆行反映状态。
- M6 前不存在 `TBD`、无主阶段、无验收证据或同一 ID 多重语义。
- 自动检查应验证阶段/ADR 中引用的业务 ID 均能在权威需求文件中找到。
