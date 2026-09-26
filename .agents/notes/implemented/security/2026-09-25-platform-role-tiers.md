# Agent Note: 三级平台角色与公司管理

Status: implemented

## Problem

叠加的 APP_MANAGER 与 COMPANY_ADMIN 让成员拥有多个全局身份，界面无法直接说明谁有完整管理能力。ACL-009 将平台治理与业务读取隔离，也不符合目前平台管理员包含公司管理员的产品语义。

## Decision

采用 APP_MANAGER ⊇ COMPANY_ADMIN ⊇ COMPANY_MEMBER 的单一全局层级，项目负责人和成员继续属于资源关系。平台角色分配只保存显式层级，普通员工无 ACTIVE 分配。V56 复用 `uq_platform_role_assignment_active`，将唯一性改为 `(company_id, user_id) WHERE status='ACTIVE'`。双角色历史行以 SYSTEM / ROLE_TIER_CONSOLIDATION 撤销 COMPANY_ADMIN，不递增用户授权版本、不写无消费者的迁移事件，保持既有会话。

`CurrentActor` 展开 APP_MANAGER 的 COMPANY_ADMIN 有效能力；内部管理授权通过 `RoleUserSnapshot.hasEffectiveRole` 同样展开。`PlatformRoleQuery` 保持返回实际分配角色，避免混淆持久化事实与有效权限。本决策推翻 ACL-009 的仅平台管理员不可读业务数据限制，部分替代[活动投影](../architecture/2026-08-30-activity-projection-contract.md)和[附件上传](../architecture/2026-08-25-attachment-upload-processing.md)中的同类限制。

`PUT /admin/members/{userId}/platform-role` 在幂等事务与治理锁内原子撤销旧层级、授予新层级，发布 v1 ROLE_REVOKED / ROLE_GRANTED，用户授权版本仅递增一次并撤销会话，成功与失败均写安全审计。同层级返回当前版本且不写入。仅近期认证的平台管理员可修改他人；禁止修改自己、提升不可用用户以及撤销最后可用平台管理员。旧授予接口发现已有任意有效角色时返回 409；break-glass 先系统撤销原公司管理员再恢复平台管理员。本地夹具只授予 APP_MANAGER。

Web 入口更名公司管理，路径 `/admin/company/{overview,members,sync-runs}`；旧 `/admin/identity/*` 链接带 query/hash 重定向。成员列表展示单层级、支持角色筛选和原子变更。

## Alternatives considered

- 保留叠加角色：无法提供单一可理解身份，授予和撤销需要多次请求并暴露中间状态。
- 在 identity_user 新增层级列：会产生与平台分配审计历史并行的权限事实，增加一致性成本。
- 在 PlatformRoleQuery 或各业务 SQL 展开：前者破坏已分配角色契约，后者将继承规则分散到多个模块。

## Consequences

平台管理员现在可以读取并管理公司业务，这是明确的授权边界扩展。数据库保证一个有效层级，已有客户端应使用原子变更接口切换角色。迁移回滚需要先处理新层级写入；撤销历史仍可区分用户与系统来源。角色变更后目标重新登录，当前操作人会话保持有效。
