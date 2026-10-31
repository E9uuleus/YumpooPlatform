# Agent Note: 项目成员自行退出

Status: implemented

## Problem

成员页只有负责人和公司管理员的移出成员能力，普通成员无法离开不再参与的项目，只能请负责人移出。直接开放治理接口给本人还要求客户端提供成员版本，并把自主退出与管理权限混在一起。

## Decision

- 新增 `POST /projects/{projectId}/leave`，项目查询通过可选只读字段 `canLeave` 表示能力。只有进行中项目的非负责人成员可以退出；同时具有公司管理员身份的项目成员也适用，只读访问的公司管理员不可退出。
- 不需要 If-Match，服务端读取调用者当前成员版本，保留并发冲突的 412；命令需要 Idempotency-Key。可见性与成员资格在幂等回调内校验，已成功退出的普通成员可用同一键重放原响应；新键仍按当前权限拒绝。负责人需先转交负责人，已结束或已归档项目需先重新打开或恢复。
- 复用[项目成员治理](2026-08-20-project-membership-governance.md)的软移除与 Project → membership 锁序，成员关系变为 REMOVED，removed_by_user_id 为本人；工时、讨论和处理人历史保留。
- 在同一事务内通过 ProjectTimerStopPort 停止本人在该项目中运行的计时，保持 Project → timer_state 锁序，不停止其他成员或其他项目的计时。无数据库迁移。
- 复用 `catalog.project_member_removed` v1、`changeSource=DIRECT`，由事件 actor 与 payload userId 相同识别自行退出，不新增事件类型。负责人沿用成员移除通知，收件箱文案为「退出了项目」。
- 项目动态与公司活动日志使用 PROJECT_MEMBER_LEFT 模板；安全审计成功为 PROJECT_MEMBER_LEFT，失败为 PROJECT_MEMBER_LEAVE_FAILED，目标类型为 PROJECT_MEMBERSHIP。

## Alternatives considered

- 把移出成员接口开放给本人：权限判断混入治理接口，且客户端仍需提供成员 ETag。
- 新增 project_member_left 事件：消费者都需要订阅新类型，但成员失效与计时停止行为与移出相同。
- 退出时清理处理人：改写历史，且与被移出时保留记录的行为不一致。

## Consequences

负责人收到通知，项目动态显示「退出了项目」。退出后无法撤销，需要负责人重新添加。前端入口由后续项目菜单提供，能力与操作权限以服务端为准。成员退出、负责人拒绝和非成员管理员拒绝由 ProjectMembershipGovernanceIT 覆盖；收件箱文案由 inboxPresentation.spec.ts 覆盖。
