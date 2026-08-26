# Agent Note: Project 原子创建与模板固化契约

Status: implemented

## Problem

Project 创建同时跨越 `catalog` 的 Project/负责人 membership、`templateworkflow` 的已发布模板快照和 `workitem` 的初始 Content。若拆成多个事务或异步补齐，调用方会观察到没有 Content 的 Project；若复制模板或负责人事实，又会造成后续成员治理和模板停用时的双重真源。客户字段的必填时机也必须与 DRAFT 配置阶段和激活阶段明确分离。

## Decision

`POST /api/v1/projects` 是 M2-04 唯一公开的 Project operation，由 `administration.application.ProjectCreationOrchestrator` 持有单一 PostgreSQL 事务。只有 `COMPANY_ADMIN` 可执行；请求以 `templateKey + templateVersion` 显式选择模板版本，模板必须保持 PUBLISHED，并通过共享行锁与并发停用串行。固定映射为 `PRODUCT_DEVELOPMENT → RND`、`PRE_SALES → PRE_SALES`、`IMPLEMENTATION → IMPLEMENTATION`、`HYPERCARE → HYPERCARE`。

`catalog.project` 保存 Company 内唯一且不可变的 code、类型、内部 MAIN Workspace 归属、负责人和模板引用；创建时固定为 `DRAFT/rowVersion=0`。请求不接受 `workspaceId`，事务内锁定 Company 唯一且 ACTIVE 的 MAIN 后自动写入其稳定 ID。负责人必须是本企业 `ACTIVE + ENABLED` User，并在同一事务同步创建 ACTIVE `project_membership`。延迟约束触发器在事务提交时保证当前 owner 始终有 ACTIVE membership，允许 M2-05 在一个事务中先建 membership 再改 owner。

`workitem.content` 是初始 Content 真源。初始化端口按锁定的模板快照创建全部 blueprint；四个 V1 模板当前均形成 REQUIREMENTS、TASKS、DEFECTS 三行。每行保存模板 key/version 和 blueprint code，初始为 ACTIVE、使用 blueprint 的默认视图且 `viewConfig={}`。只约束 Project 内 code 唯一，不约束同一 Project 内 Content 类型唯一，为 M2-09 的后续 Content 管理保留空间。M2-19A 起同一初始化事务还建立 [Project 级状态/优先级标签目录](../data/2026-08-26-project-work-item-label-catalog.md)，因此调用方不会观察到缺少目录的 Project。

客户文本在创建时去除首尾空白，空串归一为 null。PRODUCT_DEVELOPMENT 的客户字段始终可选；PRE_SALES、IMPLEMENTATION、HYPERCARE 也允许缺少 `customerName` 创建 DRAFT，PPM-007 的必填检查延至 M2-06 激活，而不是阻止草稿初始化。

创建事务在初始 Content 后追加 `PROJECT_CREATED` Security Audit、`catalog.project_created` 与 `catalog.project_template_applied` Outbox，再完成幂等记录。审计和事件只包含 Project 标识、code/name、类型、生命周期、Workspace、owner、模板引用和 Content 数量/编码，不携带 description、客户名或联系备注。幂等记录同时保存原始响应文本和 JSONB，确保成功重放的 201 body 与 ETag 字节级一致。

M2-04 不实现列表、详情、PATCH、激活、成员管理、Content API/View Config 或 Activity 投影。这些边界分别保留给 M2-05、M2-06、M2-09、M2-20；Workspace `visibleProjectCount` 在 M2-06 前继续为 0。

## Alternatives considered

- 创建 Project 后异步初始化 Content：拒绝。失败重试会暴露半成品并需要额外补偿状态。
- 由 Catalog 直接读取模板表或写 Content 表：拒绝。跨模块只能通过公开端口，事务所有权不等于数据所有权。
- 只保存模板 key、不保存版本和 blueprint provenance：拒绝。模板后续版本不能静默改变既有 Project 的解释。
- 把 `PROJECT_OWNER` 写入平台角色表：拒绝。Project owner 和 membership 的唯一真源属于 Catalog，平台角色生命周期不同。
- 非研发 Project 在创建时强制 customerName：拒绝。DRAFT 是配置阶段，激活才是业务可用边界。
- 事件复制 description 或客户联系文本：拒绝。Activity 和集成消费者只需要安全摘要，复制会扩大敏感数据面。

## Consequences

客户端创建时必须携带 UUID `Idempotency-Key`，但不提交或选择 Workspace；成功只收到完整 Project，不收到 Content 摘要，响应固定为 201、`ETag: "0"` 和稳定 Location。MAIN 缺失属于内部不变量失败；owner、模板、类型映射或重复 code 返回字段级 422；权限失败为 403；幂等键异体复用或处理中为 409。Content、Audit、任一 Outbox 或持久化写入失败都返回 500，且 Project、membership、Content、审计、事件和幂等占位全部回滚。MAIN 归属的长期理由由 [MAIN 单工作空间契约](2026-08-23-main-workspace-contract.md) 拥有。

后续里程碑必须复用 Project 和 membership 真源、模板 provenance 与跨模块端口，不得增加临时 owner 角色、重复模板快照表或第二个创建接口。M2-05 重指派必须满足延迟 membership 不变量；M2-06 激活补齐非研发 customerName 和真实可见性；M2-09 才公开 Content CRUD；M2-20 消费现有 Outbox 形成 Activity。
