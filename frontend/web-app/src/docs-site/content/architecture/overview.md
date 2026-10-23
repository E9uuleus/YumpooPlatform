---
title: 架构总览
description: 一个部署单元、四条架构原则和完整技术栈。
order: 10
audience: [developer]
---

读完你会知道：Yumpoo 由哪些部分组成、它们怎么部署，以及贯穿整个仓库的四条原则。

::: diagram system-context
:::

## 一个部署单元

生产环境只有一个部署单元：

- **Spring Boot 单 JAR**：模块化单体，提供 `/api/v1`、会话、事件分发和定时任务；
- **静态 Web 应用**：Vue SPA，由反向代理直接提供，深链接回落到 `index.html`；
- **PostgreSQL**：业务数据位于 `yumpoo` schema。

桌面客户端不是独立系统：它加载同一个在线 Web 应用，另外从同一站点的桌面更新源获取安装包。企业微信负责 Web 登录（OAuth）、桌面登录（PKCE 交接）和通讯录同步。

## 技术栈

| 层 | 技术与版本 |
|---|---|
| 后端 | Java 21、Spring Boot 4.1.1、Spring Security、Spring JDBC、Flyway |
| Web | Vue 3.5、vue-router 5.4、Element Plus 2.14、Vite 8.3、TypeScript 5.9、Tiptap 3 |
| 桌面 | Electron 43.7、TypeScript，独立 main / preload 与受限 IPC 桥 |
| 数据库 | PostgreSQL 17.10 |
| 契约与工具 | OpenAPI 3.0、生成式 TypeScript Fetch 客户端、JSON Schema 事件契约、pnpm 11.16 workspace、Node 24.14 |
| 测试 | Vitest、Node test runner、JUnit、ArchUnit、Testcontainers、Playwright（本地冒烟） |

精确版本以 [package.json](../../../../../../package.json)、[frontend/web-app/package.json](../../../../../../frontend/web-app/package.json)、[backend/pom.xml](../../../../../../backend/pom.xml) 和 [.node-version](../../../../../../.node-version) 为准。

## 仓库目录

| 目录 | 内容 |
|---|---|
| `backend/` | 后端模块、迁移与测试 |
| `frontend/web-app/` | Web 应用与本文档站 |
| `desktop/desktop-shell/` | Electron 主进程与 preload |
| `packages/` | 生成的 API 客户端、preload 类型契约 |
| `contracts/` | OpenAPI、事件 Schema、金样例与迁移例外登记 |
| `tools/` | CI、契约、架构检查、发布与验证工具 |
| `deployment/windows/` | Windows 部署模板与运维手册 |
| `.agents/notes/` | 长期决策记录 |

## 四条原则

1. **契约先行**：REST 接口先写 OpenAPI，事件先写 JSON Schema；前端客户端由契约生成，见 [API 契约与客户端生成](api-contracts.md)。
2. **模块边界由测试强制**：后端模块之间只能通过 `api` 层调用，依赖方向写在架构测试里，违反即构建失败，见 [后端模块化单体](backend-modules.md)。
3. **迁移只扩展**：数据库迁移默认只新增，保证上一个版本可以快速回退，见 [数据与迁移](data.md)。
4. **浏览器与桌面共用同一 Web 应用**：桌面壳不内置业务页面，只提供受限的本机能力，见 [桌面客户端架构](desktop.md)。
