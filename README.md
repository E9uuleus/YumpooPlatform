# YumpooPlatform

YumpooPlatform 一期采用单部署的模块化单体后端、共享 Vue SPA，以及只加载同一在线 SPA 的 Electron 桌面壳。

## M0-10 乐观锁与幂等最小闭环

```text
backend/                     Spring Boot 模块化单体
contracts/openapi/           OpenAPI 3.0.3 唯一契约与错误样例
frontend/web-app/            Vue 3 在线 SPA
desktop/desktop-shell/       Electron main/preload 在线壳
packages/api-client/         由 OpenAPI 生成的 TypeScript Fetch SDK
packages/preload-contract/   Web 与 preload 共享的最小类型契约
tools/architecture/          Node 工作区边界门禁
tools/openapi/               lint、生成漂移和兼容性检查工具
tools/verification/          契约生成、三端联合验证与桌面冒烟
```

本仓库使用 Java 21、Maven Wrapper 3.9.9、Node.js 24.14.0 和 pnpm 11.16.0。Node 工作区只有根目录一份 `pnpm-lock.yaml`，所有声明依赖均锁定精确版本。后端数据库基线是 PostgreSQL 17.10、Spring JDBC 和 Flyway，业务对象统一进入 `yumpoo` schema。

## 安装与联合验证

```powershell
pnpm install --frozen-lockfile
pnpm verify:m0-10
pnpm smoke:desktop
```

`verify:m0-10` 先验证 OpenAPI、生成客户端及生成物漂移，再执行后端 Maven Verify，以及 Node 工作区的 Lint、类型检查、边界负向测试、单元测试和生产构建。后端 Verify 会通过 Testcontainers 启动 `postgres:17.10-alpine`，验证 Flyway V1/V2、乐观锁竞争和幂等记录闭环；Docker 不可用时直接失败，不使用 H2 或跳过真实库验收。`smoke:desktop` 在随机回环端口启动已构建的 Vue SPA，并让隐藏的 Electron 窗口完成一次真实加载后正常退出。

也可以分别验证：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
pnpm check:openapi
pnpm generate:api-client
pnpm check:api-client
pnpm check:openapi-compat -- <baseline-openapi-file>
pnpm verify:node
```

本次新增的契约在首次合入 `dev` 后即成为初始兼容性基线。`check:openapi-compat` 使用固定的 openapi-diff 2.1.6；传入历史 OpenAPI 文件后，任何不兼容变更都会失败，M0-18 再把基线提取和该命令接入 PR CI。

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

共享和生产环境还必须设置独立迁移账号的 `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD`。所有密码都从外部配置注入，不进入仓库。M0-10 仍不新增生产业务端点，当前仅对外提供：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 架构边界

- 后端 13 个一级模块统一采用 `api/application/domain/infrastructure` 四层；ArchUnit 对层级方向、允许依赖矩阵、跨模块内部实现访问和循环依赖执行硬门禁。
- Web/renderer 不得导入 Node、Electron 或 desktop-shell 实现；可运行时依赖浏览器 Fetch 边界的 `@yumpoo/api-client`，只能以 type-only 方式读取 `@yumpoo/preload-contract`。
- OpenAPI 是请求、响应、错误、分页和客户端生成的唯一契约源；生成目录禁止手工修改，漂移由验证脚本阻止。
- preload 不使用原始 IPC 或 Node built-in，只暴露冻结的 `window.yumpooDesktop` 客户端标识。
- Electron main/preload/Web 分离编译；新窗口、权限请求和跨源导航默认拒绝。

## 当前与后续范围

M0-10 在既有 `/api/v1` 契约底座上增加 `idempotency_record` 技术表、稳定请求哈希、条件写入守卫和事务型幂等命令执行器。相同幂等键与请求可重放已完成结果，不同请求复用同一键或读取到已存在的处理中记录时返回稳定冲突；PostgreSQL 集成测试证明同一版本并发写入只有一次成功。

认领、业务回调和成功结果保存位于同一事务；回调失败会同时回滚业务事实与认领记录，重试可以重新执行。表中的 `lease_until` 和 `expires_at` 仅预留为一期崩溃恢复与清理元数据；M0-10 不读取这些字段来接管、自动清理或持久化失败状态。本切片也不创建业务表、正式业务 Controller 或 OpenAPI 路径；具体聚合的资源可见性复查、Repository 条件更新和 HTTP 端点由后续业务切片接入。M0-11 才实现 requestId 向领域事件和 Outbox 的贯穿；真实认证、客户端版本策略、结构化日志、登录交接、深链、通知、升级和安装器同样不在 M0-10 范围内。完成 M0-10 仍不代表完整 M0 里程碑退出。
