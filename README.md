# YumpooPlatform

YumpooPlatform 一期采用单部署的模块化单体后端、共享 Vue SPA，以及只加载同一在线 SPA 的 Electron 桌面壳。

## M0-12 企微 OAuth 回调安全验证骨架

```text
backend/                     Spring Boot 模块化单体
contracts/openapi/           OpenAPI 3.0.3 唯一契约与错误样例
contracts/events/            内部事件信封 Schema、事件目录与探针样例
frontend/web-app/            Vue 3 在线 SPA
desktop/desktop-shell/       Electron main/preload 在线壳
packages/api-client/         由 OpenAPI 生成的 TypeScript Fetch SDK
packages/preload-contract/   Web 与 preload 共享的最小类型契约
tools/architecture/          Node 工作区边界门禁
tools/openapi/               lint、生成漂移和兼容性检查工具
tools/events/                事件目录、Schema 与正反样例校验工具
tools/verification/          契约生成、三端联合验证与桌面冒烟
evidence/m0-12/              真实企微验证的 Schema、示例与脱敏证据
```

本仓库使用 Java 21、Maven Wrapper 3.9.9、Node.js 24.14.0 和 pnpm 11.16.0。Node 工作区只有根目录一份 `pnpm-lock.yaml`，所有声明依赖均锁定精确版本。后端数据库基线是 PostgreSQL 17.10、Spring JDBC 和 Flyway，业务对象统一进入 `yumpoo` schema。

## 安装与联合验证

```powershell
pnpm install --frozen-lockfile
pnpm verify:m0-12
pnpm smoke:desktop
```

`verify:m0-12` 先校验真实验证证据的 Schema、示例和当前文件，再沿用 M0-11 的全部门禁：事件目录、JSON Schema 与正反样例，OpenAPI、生成客户端及生成物漂移，后端 Maven Verify，以及 Node 工作区的 Lint、类型检查、边界负向测试、单元测试和生产构建。后端 Verify 会通过 Testcontainers 启动 `postgres:17.10-alpine`；Docker 不可用时直接失败，不使用 H2 或跳过真实库验收。`smoke:desktop` 在随机回环端口启动已构建的 Vue SPA，并让隐藏的 Electron 窗口完成一次真实加载后正常退出。

也可以分别验证：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
pnpm validate:event-contracts
pnpm check:openapi
pnpm generate:api-client
pnpm check:api-client
pnpm check:openapi-compat -- <baseline-openapi-file>
pnpm verify:node
```

本次新增的契约在首次合入 `dev` 后即成为初始兼容性基线。`check:openapi-compat` 使用固定的 openapi-diff 2.1.6；传入历史 OpenAPI 文件后，任何不兼容变更都会失败，M0-18 再把基线提取和该命令接入 PR CI。

## M0-12 企微真实验证

M0-12 诊断路由默认不存在。只有同时设置 `SPRING_PROFILES_ACTIVE=m0-12-live` 和 `YUMPOO_M012_WECOM_ENABLED=true` 时，后端才注册以下路径：

- `GET /_m0/m0-12/wecom/authorize`
- `GET /_m0/m0-12/wecom/callback`

运行真实验证前，在后端进程和验证脚本所在的受控环境中配置数据库变量，以及以下值；真实值、授权 code、token、Secret 和完整 callback query 不得写入仓库、命令输出或工单：

- `YUMPOO_M012_WECOM_CORP_ID`
- `YUMPOO_M012_WECOM_AGENT_ID`
- `YUMPOO_M012_WECOM_APP_SECRET`
- `YUMPOO_M012_WECOM_CALLBACK_URI`（固定 HTTPS callback，不带 query）
- `YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS`（逗号分隔的测试成员白名单）
- `YUMPOO_M012_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种字符，不得使用占位值或复用应用 Secret）

先通过现有 HTTPS 反向代理启动启用了 `m0-12-live` profile 的后端，再在具有相同配置的另一终端执行：

```powershell
pnpm verify:m0-12:live
```

验证脚本默认从 callback URI 的 origin 访问服务，也可用同源 HTTPS 的 `YUMPOO_M012_LIVE_BASE_URL` 覆盖。脚本会自动检查伪造 state、错误 nonce、无效 code 和已消费 attempt 重放均返回统一 401，然后要求同一白名单成员完成两次授权；粘贴的单行签名收据不会回显。只有两份收据验签通过且企业、成员指纹稳定一致后，脚本才会把 `evidence/m0-12/live-verification.json` 从 `NOT_RUN` 更新为脱敏 `PASS`。该证据不保存原始身份、requestId、签名、code、token 或完整供应商响应；未在真实企业环境执行时必须保持 `NOT_RUN`。

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

共享和生产环境还必须设置独立迁移账号的 `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD`。所有密码都从外部配置注入，不进入仓库。M0-12 不新增正式生产业务端点；未启用诊断双重门禁时仅对外提供：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 架构边界

- 后端 13 个一级模块统一采用 `api/application/domain/infrastructure` 四层；ArchUnit 对层级方向、允许依赖矩阵、跨模块内部实现访问和循环依赖执行硬门禁。
- Web/renderer 不得导入 Node、Electron 或 desktop-shell 实现；可运行时依赖浏览器 Fetch 边界的 `@yumpoo/api-client`，只能以 type-only 方式读取 `@yumpoo/preload-contract`。
- OpenAPI 是请求、响应、错误、分页和客户端生成的唯一契约源；生成目录禁止手工修改，漂移由验证脚本阻止。
- preload 不使用原始 IPC 或 Node built-in，只暴露冻结的 `window.yumpooDesktop` 客户端标识。
- Electron main/preload/Web 分离编译；新窗口、权限请求和跨源导航默认拒绝。

## 当前与后续范围

M0-11 在既有幂等事务底座上增加稳定内部事件信封、`outbox_event`、`outbox_consumer_receipt` 和可配置 dispatcher。`TransactionalEventPort` 只能加入现有事务，业务失败时事实、幂等记录与事件一并回滚；HTTP 根请求的 `requestId` 同时成为 `correlationId`，消费与派生事件继续继承关联链路。内部事件契约位于 `contracts/events`，本切片只登记测试探针事件，不增加正式 HTTP 路径或 OpenAPI operation。

M0-12 增加可复用的企微身份网关、持久化 OAuth attempt 和仅用于真实验证的双门禁诊断路由。state 与浏览器 nonce 都只以哈希持久化，attempt 在访问企微前原子消费；成功只返回 HMAC 签名脱敏收据，不创建 User、ExternalIdentity 或 LoginSession。`DEPENDENCY_UNAVAILABLE` 是公共 503 契约，但诊断路由本身不进入正式 OpenAPI paths。

worker 默认每秒轮询，批量 50、并发 2、租约 5 分钟。领取覆盖到期的 `PENDING/RETRY` 与租约过期的 `PROCESSING`，并以 owner + token 防止旧 worker 回写；低版本未完成或 `DEAD` 会阻塞同聚合高版本。每个消费者的数据库效果与 receipt 在独立事务中提交，多消费者重试会跳过已完成者；五档退避后第六次失败进入 `DEAD`。控制台使用 Spring Boot 内建 Logstash JSON 日志，并在请求和消费边界写入受控关联字段。

正式身份绑定与会话、Security Audit、Activity 投影、通知投递、人工重排、监控告警、Outbox 清理和管理页面仍留给后续切片。完成 M0-12 也不代表完整 M0 里程碑退出。
