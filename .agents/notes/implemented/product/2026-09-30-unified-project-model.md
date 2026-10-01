# Agent Note: 统一项目模型与内置默认结构

Status: implemented

## Problem

四套项目类型与模板、客户信息门槛和 DRAFT 激活流程让创建与日常工作分离，且扩大了治理与契约的维护面。项目需要作为统一协作容器，由每个有效成员直接创建。该决策完整取代旧创建契约；查询、成员治理、类别和标签目录仍由各自记录拥有。

## Decision

项目仅有 ACTIVE、ARCHIVED 两种生命周期。删除类型、模板引用、客户字段和激活接口，整体删除 templateworkflow 模块。创建请求只含 name、description，任意本企业 ACTIVE + ENABLED 成员可创建，创建人即负责人，初始版本为 0。项目编码保持不可变，工作项编号仍为项目编码加序号。

ProjectCreationOrchestrator 继续持有单一 PostgreSQL 事务，依次创建 Project、ACTIVE owner membership、默认类别与标签目录、安全审计、catalog.project_created@2 和幂等成功结果。通过公开端口跨模块编排，事务所有权不等于数据所有权。延迟约束仍确保 owner 具有 ACTIVE membership，重指派可以在同一事务中先创建 membership 再更新 owner；不把项目负责人复制到平台角色表。

创建自动归属本企业唯一且 ACTIVE 的 MAIN Workspace，不接受 workspaceId。创建事务以 FOR UPDATE 锁定 MAIN，在锁内统计匹配 ^P[0-9]{3,9}$ 的编码最大数值加一，至少三位补零，从 P001 开始。手工历史编码保持原值且不参与计数；唯一冲突属于内部不变量失败，返回 500，不对用户暴露 code 字段错误。MAIN 缺失同样为内部不变量失败。MAIN 归属理由继续由 [MAIN 单工作空间契约](2026-08-23-main-workspace-contract.md) 拥有。

DefaultProjectStructure 是后端初始化的唯一常量源：受保护的 REQUIREMENTS/需求/BRIGHT_BLUE/10、TASKS/任务/BRIGHT_GREEN/20、DEFECTS/缺陷/DARK_RED/30；状态 NOT_STARTED/未开始/GRAY/TODO/0（受保护）、IN_PROGRESS/进行中/ORANGE/IN_PROGRESS/10、STUCK/卡住/RED/IN_PROGRESS/20、DONE/已完成/GREEN/DONE/30、CANCELED/已取消/AMERICAN_GRAY/CANCELED/40；优先级 LOW/低/BLUE/10、MEDIUM/中/TEAL/20、HIGH/高/ORANGE/30、URGENT/紧急/RED/40。前端 DEFAULT_PROJECT_STRUCTURE 逐项镜像，预览无需请求模板。类别和标签允许项目内后续配置，既有项目的类别和标签不迁移、不改写，初始化不约束类别类型唯一。

创建仍要求 UUID Idempotency-Key。成功返回完整 Project、201、稳定 Location、ETag "0"；幂等存储保留原始响应文本和 JSONB，保证相同成功请求的响应字节一致。内容、审计、事件或任一持久化步骤失败时，整个事务及幂等占位回滚。安全审计和新事件只包含 projectId、workspaceId、code、name、ACTIVE、ownerUserId、initializedContentCount，不携带 description 或客户联系文本；数量只约束至少一项，不把当前三个类别写死在 wire 契约。

V61 将 DRAFT 更新为 ACTIVE，删除退役字段和模板表，收敛约束；清理退役事件的 outbox/receipt，仅清理 project_created v1，保留 v2。历史项目 Activity 和安全审计保持不变，旧创建、激活、模板应用摘要继续只读渲染。事件源码退役检查仅禁止已无任何当前版本的事件类型，旧版本退役不阻止新版本继续使用同一类型字面量。OpenAPI 使用精确旧、新规范哈希例外，冻结事件和既有例外均不改写。

前端创建入口仅在管理项目页，使用 min(800px, calc(100vw - 32px)) Modal；没有新建路由、编码输入、侧栏或顶栏入口。设置页保留名称、描述、只读编码、项目信息和生命周期操作，连接区仅渲染空态，后续 PR 接入。

部分取代并保留：[项目查询](2026-08-20-project-query-and-activation-contract.md)、[成员治理](2026-08-20-project-membership-governance.md)、[标签目录](../data/2026-08-26-project-work-item-label-catalog.md)、[类别目录](2026-09-02-work-item-category-catalog.md)、[Content 管理](2026-08-22-content-management-contract.md)、[工作项核心](../architecture/2026-08-22-work-item-core-contract.md)。

## Alternatives considered

- 创建后异步补齐类别和标签：拒绝。会暴露半成品，失败重试需要额外补偿状态。
- Catalog 直接查询模板或写 Content：拒绝。复用跨模块端口及单事务，保持数据所有权。
- 继续保留四套类型、版本与模板 provenance：拒绝。统一模型不再以模板决定项目含义；既有类别与标签保持独立事实，未来模板不得静默改变它们。
- 用只读客户字段或 DRAFT 保留旧流程：拒绝。新增项目立即可用，客户名不再决定激活门槛；原先将客户必填推迟到激活的理由由移除该门槛替代。
- 用户自行输入编码或另加编码序列表：拒绝。现有 MAIN 行锁足以串行化本企业创建，保留存量编码与工作项编号格式。
- 把项目负责人写入平台角色表或复制 owner：拒绝。负责人和 membership 真源仍在 Catalog，生命周期不同。
- 事件复制描述或客户联系文本：拒绝。消费者只需要安全摘要，复制扩大敏感数据面。

## Consequences

创建、列表、详情、设置、归档、恢复、成员管理与 Activity 共享统一模型，归档仍为只读，权限不因移除 DRAFT 扩大。旧客户端提交退役字段被拒绝，前端、契约、生成客户端需配套交付。幂等键异体复用或处理中仍为 409，权限失败仍为 403，字段校验仍为 422，内部失败仍为 500。

旧模板版本不再是项目解释来源；历史迁移和已封存证据保持冻结。失效里程碑资产检查转为历史结构验证，当前业务能力由创建、并发编码、迁移、HTTP、Activity 和前端测试验证。当前项目类别、标签、成员、审计、Activity 在 V61 测试中逐项验证保留。
