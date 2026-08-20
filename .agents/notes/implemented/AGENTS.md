# AGENTS.md — Implemented Agent Notes

本目录中的 Agent Note 描述已经交付的长期决策。遵循[根规则](../../../AGENTS.md)和[统一格式](../README.md#文件格式)。

## 与实际实现保持同步

当既有决策的路径、符号、默认值或实现机制发生事实性变化时，在同一变更中更新原 Note；直接改写过时事实，不追加变更历史。

不得把原 Note 改写成相反决策。推翻或替换决策时创建新的 owner，并交叉链接。部分 supersession 保留双方；完整 supersession 只有在新 owner 吸收全部独特理由、替代方案、后果和约束后，才通过 [`archive-agent-notes`](../../skills/archive-agent-notes/SKILL.md) 将旧 Note 归档。

implemented Note 不得直接删除，也不得保留 `Proposal`、`Plan`、`Migration plan` 或 `Acceptance criteria` 等提案期 H2。
