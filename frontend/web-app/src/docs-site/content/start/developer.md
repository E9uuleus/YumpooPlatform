---
title: 本地开发
description: 准备环境、启动后端与 Web、运行检查，以及仓库约定速览。
order: 40
audience: [developer]
---

这篇面向参与本仓库开发的同事。命令以 Windows PowerShell 为例，都从仓库根目录执行；macOS / Linux 把 `.\backend\mvnw.cmd` 换成 `./backend/mvnw`。

## 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| Node.js | 24.14.0 | 与 [.node-version](../../../../../../.node-version) 一致 |
| pnpm | 11.16.0 | 未安装时 `npm install -g pnpm@11.16.0` |
| JDK | 21 | 设置好 `JAVA_HOME`；Maven 由 Wrapper 下载 |
| PostgreSQL | 17.10 | 本机实例或 Docker 容器 |
| Docker | Linux containers | 后端集成测试用 Testcontainers 启动 PostgreSQL |

## 启动本地环境

::: steps
1. 安装依赖：`pnpm install --frozen-lockfile`。
2. 准备数据库：已有 PostgreSQL 时创建一个 UTF-8 数据库；也可以用 Docker 启动一个 `postgres:17.10-alpine` 容器（完整命令见根目录 [README](../../../../../../README.md)）。
3. 在同一个终端里设置 `SPRING_DATASOURCE_*`、`SPRING_FLYWAY_*`，以及 `SPRING_PROFILES_ACTIVE=local`、`YUMPOO_LOCAL_AUTH_ENABLED=true`，然后启动后端。
4. 另开终端运行 `pnpm dev:web`，浏览器访问 Web 开发服务器。
5. 需要桌面端时，保持 Web 运行，再开一个终端运行 `pnpm dev:desktop`。
:::

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/yumpoo_platform'
$env:SPRING_DATASOURCE_USERNAME = 'yumpoo_local'
$env:SPRING_DATASOURCE_PASSWORD = '<本地密码>'
$env:SPRING_FLYWAY_URL = $env:SPRING_DATASOURCE_URL
$env:SPRING_FLYWAY_USER = $env:SPRING_DATASOURCE_USERNAME
$env:SPRING_FLYWAY_PASSWORD = $env:SPRING_DATASOURCE_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:YUMPOO_LOCAL_AUTH_ENABLED = 'true'
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

::: tip 本地登录夹具
`local` profile 加 `YUMPOO_LOCAL_AUTH_ENABLED=true` 时，后端预置“本地测试管理员”，首次访问自动建立会话，不需要企业微信。这个夹具只允许 `local` profile 和回环地址，不能和企业微信登录同时启用。
:::

### 本地端口

| 服务 | 地址 |
|---|---|
| 后端 | `http://127.0.0.1:8100` |
| Web 开发服务器 | `http://127.0.0.1:18173` |
| Web 构建预览 | `http://127.0.0.1:18174` |
| Electron | 加载 Web 地址，不额外监听端口 |

Vite 把 `/api` 代理到后端。确认后端就绪：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8100/actuator/health/readiness'
```

## 常用命令

| 命令 | 作用 |
|---|---|
| `pnpm run ci` | 本地跑完整 CI 计划 |
| `pnpm ci:contracts` / `ci:static` / `ci:backend` | 分别跑契约、静态检查和后端阶段 |
| `pnpm test` | 全部前端与工具测试 |
| `pnpm lint`、`pnpm typecheck` | 代码规范与类型检查 |
| `pnpm generate:openapi` | 把 `contracts/openapi/yumpoo-v1/` 的分域源合并成 `yumpoo-v1.yaml` |
| `pnpm generate:api-client` | 由 OpenAPI 生成 TypeScript API 客户端 |
| `pnpm doc-sync` | 校验 Markdown 链接与 Agent Note 结构 |

改了 OpenAPI 分域源后，先 `generate:openapi`，再 `generate:api-client`，两份生成物都要提交。详见 [API 契约与客户端生成](../architecture/api-contracts.md)。

## 本地端到端冒烟

`pnpm test:e2e` 用系统 Edge 或 Chrome 跑一组串行的浏览器冒烟用例：构建 Web，启动独立的 PostgreSQL 容器、后端 JAR 和 Web 预览，任何控制台错误、页面错误或接口 5xx 都会让用例失败。

- 前置：Docker、已打包的后端 JAR（`.\backend\mvnw.cmd -f backend/pom.xml -DskipTests package`）、系统 Edge 或 Chrome；运行前释放 8100 和 18174 端口。
- `pnpm test:e2e -- --skip-web-build` 复用已有的 Web 构建。
- 只在本地运行，不属于 CI 合并门禁。

## 仓库目录速览

| 目录 | 内容 |
|---|---|
| `backend/` | Spring Boot 模块化单体、数据库迁移与测试 |
| `frontend/web-app/` | Vue Web 应用，也是这份文档的源码所在 |
| `desktop/desktop-shell/` | Electron 主进程与 preload |
| `packages/` | 生成的 API 客户端、preload 类型契约 |
| `contracts/` | OpenAPI、事件 Schema 与金样例 |
| `tools/` | CI、契约生成、架构检查、发布与打包工具 |
| `.agents/notes/` | Agent Notes：长期决策与理由 |

## 分支与 PR 约定

- `dev` 是集成分支，`main` 是已发布线。功能从最新 `dev` 建 `feature/<名称>` 或 `fix/<名称>` 分支，紧急修复从 `main` 建 `hotfix/<名称>`。
- 所有变更都经 PR 合入：功能与修复 PR 指向 `dev` 并 squash 合并；不能直接推送 `dev` 或 `main`。
- PR 正文必须有「## 发布说明」段，每行 `- 类别｜范围：描述`，面向使用者书写；没有可见影响时只写 `- 无`。
- 提交信息格式：`<type>(<scope>): <变更内容> (<步骤编号>)`。

完整流程见 [交付与发布](../architecture/delivery.md)。
