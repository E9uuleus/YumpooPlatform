# Agent Note: 迁移回退安全分类与只扩展默认

Status: implemented

## Problem

发布上线后发现问题时，需要能在几分钟内回到上一版本。Flyway 只前向执行迁移，社区版没有 undo；回到上一版本 JAR 只有在新版本新增的迁移不影响旧代码读写时才安全。此前仓库只约束迁移历史不可改写、版本向前追加，没有区分迁移对上一版本代码的兼容性，也没有机器可读的结果供部署回退判定。

## Decision

[迁移策略](../../../../tools/ci/migration-policy.mjs)在 `ci:contracts` 中对相对基线新增的迁移逐语句分类，基线与其他契约检查相同：PR 取 base SHA，push 取 `before`。

- 白名单内为只扩展：新建表、普通索引、序列、类型、视图与函数、注释、授权、会话设置，可空或带默认值且不带内联 `CHECK`/具名约束的新增列，以及对同一迁移内新建表的任意操作。「新建」必须可证明：`CREATE TABLE IF NOT EXISTS` 不算，表名按 PostgreSQL 规则（未加引号折叠为小写，带引号的保留大小写并还原 `""`）逐段连同 schema 精确匹配，`"yumpoo.x"` 与 `yumpoo.x` 互不相同，其他 schema 的同名表或未限定写法不能豁免业务表。
- 白名单外一律为破坏性：删除、改名、类型或约束收紧、已有表的唯一索引与约束、修改已有表数据、`DO` 块、`OR REPLACE` 等。无法解析的迁移同样按破坏性处理，不确定时失败关闭。
- 破坏性迁移默认使 CI 失败。作者拆分为 expand/contract 两次发布：先发布新旧代码都能使用的扩展，下一版本再收缩。确需一次完成时，在只追加的[例外清单](../../../../contracts/migrations/rollback-safety-exceptions.json)登记精确条目：文件路径、LF 归一化后的 SHA-256、分类和理由。`breaking` 条目必须链接已实施的 Agent Note；`expand` 条目用于经审阅的误报。条目绑定原始字节，迁移内容一旦改动，例外即失效。
- [服务器包](../../../../tools/verification/package-m1-15-win.mjs)写入 `windows/release-metadata.json`，记录每个迁移的版本、路径、摘要和最终分类（例外优先），[包验证](../../../../tools/verification/verify-m1-15-package.mjs)核对它与包内迁移集合及摘要一致。
- `spring.flyway.ignore-migration-patterns: "*:future"` 显式声明：旧 JAR 遇到更高版本的已应用迁移时继续启动，checksum、缺失和待执行迁移的校验保持不变。显式声明避免依赖 Flyway 默认值被后续配置无意改变。
- 已交付的历史迁移不追溯门禁。元数据同样给出它们的分类，但只有两个版本之间新增的迁移影响回退判定。

## Alternatives considered

- 用上一发布产物的 JAR 对新 schema 做动态冒烟：判定最可靠，但需要下载上一版本产物并多跑一轮集成环境。当前以静态分类作为门禁，动态冒烟可以在其上追加。
- 只靠人工评审：难以在每个 PR 中稳定执行，而且回退判定需要机器可读的结果。
- 回退时反向执行迁移：Flyway 社区版不支持 undo，反向 SQL 还会丢弃开放访问后写入的业务数据，与发布流程保留新业务事实的边界冲突。
- 关键字黑名单：新语法和复杂语句会漏判；白名单在不确定时失败关闭。

## Consequences

- 同一 `ALTER TABLE` 内新增列并只约束这些新列的 `CHECK` 等安全组合会被误报，需要登记 `expand` 例外。
- 同一迁移内建表与后续语句应统一使用 `yumpoo.` 限定写法；混用未限定和限定写法会被保守判为破坏性。
- 分类只覆盖 schema 形态。数据语义变化，例如向已有文本列写入旧代码不认识的新取值，仍需作者判断，必要时登记 `breaking`。
- 例外清单与 OpenAPI 精确例外一样只追加；改写或删除已有条目由迁移策略按基线拒绝。
- 部署侧如何使用元数据，由[发布部署决策](../process/2026-10-14-windows-release-deployment.md)负责。
