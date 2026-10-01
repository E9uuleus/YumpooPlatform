# Agent Note: 删除产品容器及项目产品关系

Status: implemented

## Problem

Product 与 Project 双容器使可见性、关系页面、负责人治理和规划中的 Feedback 聚合相互耦合。跨项目协作不再以长期 Product 主数据为入口，因此保留只读产品、空反馈模块或关系 API 会继续制造第二套业务事实与维护义务。

## Decision

删除 Catalog Product 聚合、Project–Product 关系、公开查询和写入端口；删除 administration 的产品生命周期、负责人问题、覆盖归档和 blocker；删除 audit 的产品事件投影与 PRODUCT/FEEDBACK 受众，以及尚未形成业务真源的 productfeedback 模块。项目不再按产品过滤或校验关联产品的激活条件。前端移除产品页面、项目产品页签、桌面和移动导航入口，生成客户端与 OpenAPI 同步删除接口、schema、能力字段和枚举，不保留 410 或空实现。

[V60](../../../../backend/src/main/resources/db/migration/catalog/V60__remove_product_concept.sql) 先锁定附件表并拒绝存在 PRODUCT_FEEDBACK/FEEDBACK_UPDATE 附件的数据集；随后清理产品 Activity、指定八类产品及关系 Outbox 事件及其消费收据、产品治理问题与覆盖记录，收紧约束，删除关系表和产品表。迁移不删除 Security Audit；其他项目、成员、模板、工作项、附件、Outbox 和治理记录保留。附件引用出现时整个迁移失败，不能用静默删除修复。

八类未冻结 v1 事件登记退役并删除目录项、schema 和全部样例：product_created、product_updated、product_archived、product_restored、product_owner_reassigned、product_linked_to_project、project_product_link_updated、product_unlinked_from_project，均位于 catalog 命名空间。OpenAPI 仅追加 `2026-09-30-remove-product-concept` 精确旧/新 SHA-256 例外；已交付迁移、冻结事件、既有例外与历史验收证据保持冻结。

项目模板、项目类型（含 PRODUCT_DEVELOPMENT）、客户字段、DRAFT 和激活本身继续遵循现有契约，属于后续统一项目模型工作。identityaccess 中 ProductDesktopAuth 指本应用桌面认证，保持原状。工作项关系聚合、服务、事件及权限没有改变。

## Alternatives considered

- 保留 Product 作为只读标签：仍需身份、可见性、历史关联和数据迁移规则，无法消除双容器负担，拒绝。
- 将 Product 降级为项目类型：仍混合长期主数据与执行容器，也会提前扩大本次项目类型变更范围，拒绝。
- 保留 Feedback 聚合或空 provider：当前不存在反馈业务真源，预留端口或静态零计数不能证明引用完整性与归档安全，取消该规划。
- 旧产品生命周期选择单一 owner_user_id 而不复制 PRODUCT_OWNER 平台角色，避免两套授权事实；所有负责人和审计用户受同企业外键约束，负责人必须同时 ACTIVE + ENABLED。失效只打开治理问题，不自动选继任者、不改变生命周期，保留这一拒绝隐式扩权的理由。
- 旧查询在 SQL 内先裁剪企业与资源范围，以相同 EXISTS 规则支撑详情、分页与总数，避免应用层后过滤泄露。管理员、负责人及有效关联项目 ACTIVE 成员的读取范围与写权限分开；归档产品仍允许负责人和管理员读历史，只有管理员能重指派和恢复。删除聚合后这些产品权限不再适用，也不转授给项目成员。
- 旧关系有独立 ID、版本、审计及软移除事实，避免关系操作冲突 Project 版本；有效 project/product/type 唯一，同产品可有不同关系类型。移除后新建 ID 而不复活旧行，保留审计与幂等边界。主关系最多一个但允许没有，切换用显式取消/设置，不自动提升第二条。拒绝把关系数组塞入项目、复活历史行或依赖排序选主的理由仍需未来独立聚合设计参考。
- 旧关系仅由 DRAFT/ACTIVE 项目 Owner 写，成员和非成员 CompanyAdmin 只读；能力响应不是授权凭据。建立要求 Product ACTIVE，解绑历史关系不受随后产品归档影响。治理读取并不等于日常业务所有权，删除关系不构成新增权限规则。

## Consequences

产品 code 原来在企业内唯一且创建后不可变；资料 PATCH 使用完整 name/description 快照，规范化无变化不增版本、不发事件。旧客户端依赖强 ETag、生命周期 If-Match 和创建/归档/恢复/重指派 UUID 幂等键；无效恢复负责人曾返回 409 INVALID_STATE_TRANSITION/OWNER_MISSING，无效创建或重指派目标返回 422 ownerUserId/INVALID_OWNER。这套产品接口随聚合整体退出，客户端需配套升级，不能把旧错误码或兼容端点当成继续服务的承诺。

旧负责人重指派把校验、条件更新、Security Audit、Outbox 与重放结果放在同一事务；跨模块只用公开端口，不直接读取 Catalog 或复制展示资料。旧关系事件仅包含稳定 ID、类型和主标记变化，生命周期事件不含描述或治理理由正文。已保存的安全审计继续保留，退役不等于改写审计历史。

旧归档在授权复核后排他锁 Product，事实写入共享锁并复核 ACTIVE；关系写入先锁 Project，再按 UUID 排序共享锁 Product，产品归档不反向锁项目。真实 blocker 对 DEVELOPMENT/SUPPORT 关系指向的不同 ACTIVE Project 计数；管理员覆盖需 10–500 字理由，保存安全快照与聚合计数而不修改项目或关系。关系先提交必须被 blocker 看见、归档先提交必须令新关系失败，是原锁序的必要理由；本次随着产品聚合整体删除，并未给其他聚合新增锁或替代空 blocker。

跨项目协作由后续连接列能力承担；该能力未在本次实现，届时由独立决策记录拥有其权限、数据和事件契约。原 M3B Feedback、解绑反馈阻断及反馈归档 provider 规划取消，不再是待补齐的本次交付项。项目归档继续使用真实工作项来源；待审批工时来源仍需后续真实 provider，已声明来源读取失败时拒绝归档。

V60 集成测试验证 V59 混合业务数据升级、全部目标清理、五类约束收紧和非目标数据及 Security Audit 保留；两类反馈附件各自阻断迁移并保留 V59 数据与版本。产品专属旧验收转为历史结构校验，当前项目生命周期、迁移、权限、前端入口与事件契约仍由回归和 CI 验证。
