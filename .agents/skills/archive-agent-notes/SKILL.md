---
name: archive-agent-notes
description: Audit, supersede, archive, or prune YumpooPlatform Agent Notes while preserving useful decision rationale and the frozen single-file archive.
---

# Archive YumpooPlatform Agent Notes

减少活动决策语料的维护成本，同时保留仍能指导未来工作的理由。年龄、字数和目标数量只能帮助发现候选项，不能决定结果。

## 读取权威规则

先完整读取 [Agent Note 治理契约](../../notes/README.md)、[归档说明](../../notes/archived/AGENTS.md)和相关 lifecycle 的 `AGENTS.md`。结合当前代码、配置、文档、测试、较新的 Agent Note 与活动入链，判断哪条记录仍拥有或约束当前决策。

## 检查 supersession

新建 Agent Note 时搜索活动树中涉及相同决策、机制或被拒绝替代方案的记录。

- 部分 supersession：保留双方，更新仍然有效的事实并交叉链接。
- 完整 supersession：新 owner 先保留旧记录全部独特理由、替代方案、后果、验证与约束，再将旧 implemented Note 归档。
- proposed 不归档；不再推进时迁移为 rejected。
- rejected 只在仍能阻止可信错误时保留，否则删除并修复活动入链。

## 判断未来价值

以下 implemented Note 保持活动：其理由、替代方案、职责边界、负面保证、持久化或 wire 语义、安全规则、兼容义务或重新引入条件仍可能指导后续变更。

以下 implemented Note 可以归档：决策已经完整交付，当前实现与文档另有权威来源，且其正文不再具有显著未来决策价值。不得为了配额归档。

## 执行单文件归档

1. 将文件从 `implemented/{class}/` 移到 `archived/{class}/`，不改变文件名。
2. 保留 `Status: implemented`，在下一行插入当天的 `Archived: YYYY-MM-DD`。
3. 修复或删除活动文档中的旧入链；不要修改归档文件的出链。
4. 运行 `pnpm run verify-archived-agent-notes --write` 追加 SHA-256 封印。
5. 运行 `pnpm run verify-archived-agent-notes`、`pnpm run doc-sync` 和相关测试。

封存后不得修改、移动或删除归档文件。普通 verifier 和 CI 始终只读。

## 报告

报告保留、归档、拒绝或删除的记录及理由，并列出所有真正边界模糊的判断。不要声称归档文件的出链仍然有效。
