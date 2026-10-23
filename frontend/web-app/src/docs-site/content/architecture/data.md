---
title: 数据与迁移
description: PostgreSQL 单 schema、JDBC 无 ORM、按模块组织的 Flyway 迁移、只扩展原则、乐观锁、软删除与附件存储。
order: 60
audience: [developer]
---

读完你会知道：数据怎样存储、迁移怎样组织与约束，以及删除和附件的数据生命周期。

## 存储

- PostgreSQL 单库单 schema（`yumpoo`）。
- 后端用 Spring JDBC 直接写 SQL，**不使用 ORM**；每个模块的 `infrastructure` 层拥有自己的表。

## 迁移

- 迁移位于 `backend/src/main/resources/db/migration/`，按所属模块分目录。
- Flyway 设置（见 [application.yml](../../../../../../backend/src/main/resources/application.yml)）：启动时校验（`validate-on-migrate`）、**禁用 clean**、忽略“未来版本”的已应用迁移（`*:future`），这样回退到旧 JAR 时不会因为新迁移而拒绝启动。

## 只扩展原则

新迁移默认必须“只扩展”：新建表或索引、可空或带默认值的新增列等，保证上一个版本可以快速回退。CI 的迁移回退安全检查会拒绝白名单外的语句。

删除、改名、类型或约束收紧、无默认值的 NOT NULL、修改已有数据等破坏性变更，拆成 expand / contract 两次发布；确需一次完成时，在 [rollback-safety-exceptions.json](../../../../../../contracts/migrations/rollback-safety-exceptions.json) 登记精确例外，并在晋升到 `main` 的 PR 上加标签。规则见 [迁移回退安全](../../../../../../.agents/notes/implemented/data/2026-10-15-rollback-safe-migrations.md)。

## 并发控制

可编辑的资源带行版本，接口以 ETag / If-Match 暴露。两个人同时修改同一工作项时，后提交的一方收到 412，界面刷新为最新值后再让用户决定。

## 删除与清除

- 业务数据以**软删除**为主：归档、移出、删除先改状态，保留审计与动态。
- 项目永久删除有 **30 天缓冲期**，到期后分阶段清除工作项、讨论、附件、工时、连接及相关投影。

## 附件

- 上传时检测类型（扩展名与文件内容魔数白名单），拒绝含宏的 Office 文档，单文件 100 MiB 流式上限。
- 通过检测的文件按 SHA-256 **内容寻址**存储，原子发布到附件目录。
- 下载总是作为附件下载，并带 `nosniff` 与沙箱 CSP，防止浏览器直接执行内容。
