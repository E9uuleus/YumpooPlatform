# AGENTS.md — Archived Agent Notes

本目录保存已由 manifest 封存的单文件历史记录，不是当前实现的权威来源。只有 implemented Note 可以迁入 `archived/{class}/`。

归档变更只允许：移动原文件、保留 `Status: implemented`、紧接状态行插入 `Archived: YYYY-MM-DD`、修复或删除活动文档中的旧入链，以及通过 [`archive-agent-notes`](../../skills/archive-agent-notes/SKILL.md) 更新 manifest。

封存后不得修改、移动或删除文件，不得更新路径、术语、格式或出链。普通模式运行 `pnpm run verify-archived-agent-notes`；只有实际归档时才运行 `pnpm run verify-archived-agent-notes --write`，CI 永不使用写入模式。
