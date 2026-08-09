# YumpooPlatform

YumpooPlatform 一期采用单部署的模块化单体后端、共享 Vue SPA，以及只加载同一在线 SPA 的 Electron 桌面壳。

## M0-08 工程与数据库骨架

```text
backend/                     Spring Boot 模块化单体
frontend/web-app/            Vue 3 在线 SPA
desktop/desktop-shell/       Electron main/preload 在线壳
packages/preload-contract/   Web 与 preload 共享的最小类型契约
tools/architecture/          Node 工作区边界门禁
tools/verification/          三端联合验证与桌面冒烟
```

本仓库使用 Java 21、Maven Wrapper 3.9.9、Node.js 24.14.0 和 pnpm 11.16.0。Node 工作区只有根目录一份 `pnpm-lock.yaml`，所有声明依赖均锁定精确版本。后端数据库基线是 PostgreSQL 17.10、Spring JDBC 和 Flyway，业务对象统一进入 `yumpoo` schema。

## 安装与联合验证

```powershell
pnpm install --frozen-lockfile
pnpm verify:m0-08
pnpm smoke:desktop
```

`verify:m0-08` 依次执行后端 Maven Verify，以及 Node 工作区的 Lint、类型检查、边界负向测试、单元测试和生产构建。后端 Verify 会通过 Testcontainers 启动 `postgres:17.10-alpine`，Docker 不可用时直接失败，不使用 H2 或跳过真实库验收。`smoke:desktop` 在随机回环端口启动已构建的 Vue SPA，并让隐藏的 Electron 窗口完成一次真实加载后正常退出。

也可以分别验证：

```powershell
backend\mvnw.cmd clean verify
pnpm verify:node
```

## 本地开发

先启动在线 SPA：

```powershell
pnpm dev:web
```

另开终端编译并启动桌面壳：

```powershell
pnpm dev:desktop
```

开发模式默认加载 `http://127.0.0.1:5173`。`YUMPOO_WEB_URL` 可以覆盖地址；开发环境只接受 `localhost` 或 `127.0.0.1` 的 HTTP 地址，生产环境必须提供无用户名密码的 HTTPS 地址。

后端默认监听 `127.0.0.1:8080`，可由 `YUMPOO_SERVER_PORT` 覆盖。运行后端还需通过环境变量提供应用数据库连接：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

共享和生产环境还必须设置独立迁移账号的 `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD`。所有密码都从外部配置注入，不进入仓库。M0-08 仅对外提供：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 架构边界

- 后端 13 个一级模块统一采用 `api/application/domain/infrastructure` 四层；ArchUnit 对层级方向、允许依赖矩阵、跨模块内部实现访问和循环依赖执行硬门禁。
- Web/renderer 不得导入 Node、Electron 或 desktop-shell 实现，只能以 type-only 方式读取 `@yumpoo/preload-contract`。
- preload 不使用原始 IPC 或 Node built-in，只暴露冻结的 `window.yumpooDesktop` 客户端标识。
- Electron main/preload/Web 分离编译；新窗口、权限请求和跨源导航默认拒绝。

## 当前与后续范围

M0-08 已建立 PostgreSQL、Flyway 和 Testcontainers 真实库测试骨架，但不创建业务表，也不包含 `/api/v1`、OpenAPI、统一错误体、requestId、幂等、Outbox、结构化日志、登录交接、深链、通知、升级或安装器。这些分别在后续 M0 步骤实现。完成 M0-08 也不代表完整 M0 里程碑退出。
