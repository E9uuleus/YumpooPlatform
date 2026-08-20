# Agent Notes

Agent Note 是由 Agent 编写和维护的长期提案与决策记录，用于保存代码、测试和普通文档难以承载的理由、替代方案、取舍、后果与验证缺口。本文件是 Agent Notes 的人类与 Agent 可读规范源；机器规则集中在 [`agent-note-policy.ts`](../../scripts/agent-note-policy.ts)。

## 目录与命名

活动 Agent Note 的标准路径是：

```text
.agents/notes/{lifecycle}/{class}/yyyy-mm-dd-topic.md
```

活动 lifecycle 是封闭集合：

- `proposed/`：尚未实施、等待评审的提案，遵循 [proposed 规则](proposed/AGENTS.md)。
- `implemented/`：已经交付、并与当前实现保持事实同步的决策，遵循 [implemented 规则](implemented/AGENTS.md)。
- `rejected/`：经过讨论后被否决的提案，遵循 [rejected 规则](rejected/AGENTS.md)。

`archived/` 是独立的冻结历史区，不属于活动 lifecycle，遵循 [archive 规则](archived/AGENTS.md)。顶层目录使用正向允许列表；不得增加其他 lifecycle。合法迁移只有：

```text
proposed -> implemented
proposed -> rejected
implemented -> archived
rejected -> delete
```

完整 supersession 时，新 owner 必须先保留旧记录全部独特理由、替代方案、后果和约束，再将旧 implemented Note 归档。部分 supersession 保留双方并交叉链接。implemented Note 不得直接删除。

文件名中的日期是主题首次提出的真实日期，迁移 lifecycle 时不得改变。日期必须是合法日历日期；topic 使用 ASCII lowercase kebab-case。同一日期和 topic 在活动目录中只能出现一次。

每条 Agent Note 只有一个 Markdown 文件。不得为 Note 增加翻译副本、配对元数据或同权多文件结构。class 目录在首条 Note 出现时创建，不要求预先建立。禁止集中式 `INDEX.md`；生命周期/class 目录与仓库搜索共同构成清单。

## Classification

class 是封闭集合，路径本身就是分类标签：

| Class | 适用决策 |
|---|---|
| `architecture` | 模块关系、运行时结构、职责边界和依赖方向。 |
| `product` | 用户或模型可见能力、产品行为与长期产品取舍。 |
| `data` | 数据模型、持久化、数据库、wire、配置与迁移格式。 |
| `security` | 身份认证、授权、隐私、凭据和信任边界。 |
| `process` | 仓库级 CI、发布、开发流程、工具和治理政策。 |
| `testing` | 测试基础设施、测试策略和长期验证政策。 |

新增 class 必须同时修改共享机器规则、本节和治理 Agent Note，不能只创建目录。

## 何时写 Agent Note

只有当变更建立、修改或推翻长期且未来维护者很可能重新讨论的决策时，才必须新增或更新 Agent Note，包括：

- 跨模块、跨服务或对外契约；
- 架构边界、职责边界或依赖方向；
- 数据库、持久化、wire、配置或迁移格式；
- 安全、权限、隐私或信任边界；
- 仓库级 CI、发布、开发或测试政策；
- 带来长期兼容、迁移、回滚或运维义务的选择；
- 存在可信替代方案、预计会被重新讨论的取舍。

以下通常不需要 Agent Note：局部且直观的缺陷修复、不改变契约的内部重构、普通测试补充、文案或格式修复、常规依赖升级、机械移动或重命名。已有 Note 拥有该决策时，更新原 Note，不创建重复记录。

作者和评审负责判断变更是否达到写入门槛。CI 只校验已经存在的 Note 是否合规，不根据 diff 猜测是否应当写 Note。

## 文件格式

所有活动 Agent Note 使用 UTF-8、无 BOM、LF，并以换行结尾。第一至第四行固定为：

```markdown
# Agent Note: <title>

Status: <status>

```

`Status:` 必须与所在 lifecycle 一致：

- `Status: proposed`
- `Status: implemented`
- `Status: rejected — <one-line reason>`

`Status:` 在正文中只能出现一次。第一节必须是 `## Problem`，所有必需章节必须恰好出现一次；允许在必需章节之间加入确有必要的技术章节，不强制完整章节顺序。

### proposed

```markdown
## Problem
## Proposal
## Alternatives considered
## Acceptance criteria
## Risks
```

### implemented

```markdown
## Problem
## Decision
## Alternatives considered
## Consequences
```

implemented Note 描述已交付事实，不保留计划式正文。以下 H2 禁止出现：`## Proposal`、`## Plan`、`## Migration plan`、`## Acceptance criteria`。

### rejected

```markdown
## Problem
## Proposal
## Alternatives considered
```

拒绝原因写在状态行；提案正文保持为被讨论时的方案。所有 lifecycle 都必须真实记录 `## Alternatives considered`，当前语料不提供旧格式例外。

### Lifecycle 迁移

`proposed -> implemented` 必须将 `Proposal` 改写为现在时的 `Decision`，并用 `Consequences`、`Testing` 或 `Verification` 记录实际交付结果。`proposed -> rejected` 保留提案正文，在状态行写明拒绝原因。

## 归档与删除

只有 implemented Note 可以归档到 `archived/{class}/yyyy-mm-dd-topic.md`。归档时保留 `Status: implemented`，紧接状态行加入 `Archived: YYYY-MM-DD`，修复或删除活动文档中的旧入链，并运行 [`archive-agent-notes`](../skills/archive-agent-notes/SKILL.md) 工作流更新 manifest。

归档后文件永久冻结：不得修改、移动、删除、更新术语或修复出链，也不得作为当前实现的权威来源。活动文档仍可把归档文件作为明确的历史链接目标。普通格式和链接检查跳过已封存 Note，冻结完整性由 [`verify-archived-agent-notes.ts`](../../scripts/verify-archived-agent-notes.ts) 独立负责。

proposed Note 永不归档；不再推进时迁移为 rejected。rejected Note 只在仍能阻止可信错误时保留，否则删除并修复入链。

本制度的决策理由记录在 [Agent Notes 治理决策](implemented/process/2026-08-20-agent-notes-governance.md)。
