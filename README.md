# YumpooPlatform

面向团队的项目协作平台，提供项目与工作项管理、成员协作和企业微信身份接入。后端采用单部署的模块化单体，浏览器与 Electron 桌面端共用同一套在线 Vue SPA；桌面端需要连接服务端。

## 主要功能

- 项目、成员和工作项类别管理，表格与看板视图、筛选和排序。
- 工作项父子关系与跨项目关联、富文本讨论、成员提及、附件和活动记录。
- 企业微信扫码登录、通讯录同步、成员账号与平台角色管理。
- 平台管理员运维中心：主机指标、会话、运行日志和告警。

## 技术栈

| 层面 | 技术 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.0、Spring Security、Spring JDBC、Flyway |
| Web | Vue 3.5、TypeScript 5.9、Vite 8.2、Element Plus 2.14、Tiptap 3 |
| 桌面端 | Electron 43.3、TypeScript，独立 main / preload 与受限 IPC 桥 |
| 数据库 | PostgreSQL 17.10，业务数据位于 `yumpoo` schema |
| 工具与契约 | pnpm workspace、Maven Wrapper 3.9.9、OpenAPI 3.0.3、生成式 TypeScript Fetch 客户端 |
| 测试 | Vitest、Node test runner、JUnit、ArchUnit、Testcontainers |

依赖精确版本以各目录的 `package.json`、根目录 `pnpm-lock.yaml` 和 [backend/pom.xml](backend/pom.xml) 为准。

## 环境要求

- Node.js **24.14.0**、pnpm **11.16.0**；未安装 pnpm 时执行 `npm install -g pnpm@11.16.0`。
- JDK **21**，正确设置 `JAVA_HOME`；Maven 由 Wrapper 下载，无需单独安装。
- PostgreSQL **17.10**，可使用本机实例或 Docker 容器。
- 后端集成测试与完整验证需要 Docker **Linux containers**，用于运行 PostgreSQL Testcontainers。
- Windows 生产部署与桌面打包使用 **Windows x64 / PowerShell**。

以下命令以 Windows PowerShell 为例，开发与构建命令从仓库根目录执行。macOS / Linux 的后端命令使用 `./backend/mvnw` 替代 `.\backend\mvnw.cmd`。

## 项目结构

```text
backend/                 Spring Boot 后端、数据库迁移与测试
frontend/web-app/        Vue Web 应用
desktop/desktop-shell/   Electron 主进程与 preload
packages/               API 客户端与共享 preload 类型契约
contracts/              OpenAPI、事件 Schema 与契约样例
deployment/windows/     Windows 部署模板、维护脚本与运行手册
tools/                  契约生成、架构检查、CI 与打包工具
scripts/                文档链接与 Agent Note 校验
evidence/               验收记录与验证证据
.agents/notes/           活动决策记录与冻结历史
```

`docs/` 用于本地文档与原型导出，受 Git 忽略规则保护，不随仓库分发。

## 本地运行

### 1. 获取代码与安装依赖

```powershell
git clone --branch dev https://github.com/E9uuleus/YumpooPlatform.git
Set-Location YumpooPlatform
pnpm install --frozen-lockfile
```

### 2. 准备数据库

已有 PostgreSQL 实例时，创建 UTF-8 数据库并在下一步填写连接信息。也可启动一个独立的本地开发容器：

```powershell
docker run --name yumpoo-postgres --detach `
  --publish 127.0.0.1:5433:5432 `
  --env POSTGRES_DB=yumpoo_platform `
  --env POSTGRES_USER=yumpoo_local `
  --env POSTGRES_PASSWORD=yumpoo_local_dev `
  --volume yumpoo-postgres-data:/var/lib/postgresql/data `
  postgres:17.10-alpine
```

该密码仅用于本机开发示例。容器已创建时使用 `docker start yumpoo-postgres`，数据保存在命名卷中。

### 3. 启动后端

在同一个 PowerShell 终端配置数据库与本地身份：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/yumpoo_platform'
$env:SPRING_DATASOURCE_USERNAME = 'yumpoo_local'
$env:SPRING_DATASOURCE_PASSWORD = 'yumpoo_local_dev'
$env:SPRING_FLYWAY_URL = $env:SPRING_DATASOURCE_URL
$env:SPRING_FLYWAY_USER = $env:SPRING_DATASOURCE_USERNAME
$env:SPRING_FLYWAY_PASSWORD = $env:SPRING_DATASOURCE_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:YUMPOO_LOCAL_AUTH_ENABLED = 'true'

.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

启动时 Flyway 自动创建并迁移 `yumpoo` schema。本地模式预置“本地测试管理员”，授予 `APP_MANAGER` 层级及公司管理能力，首次访问自动建立正常 Session / CSRF 会话，无需企业微信登录。该模式仅允许 `local` profile 与回环地址，不能同时启用企业微信或受控身份提供者。

另开终端确认服务就绪：

```powershell
Invoke-RestMethod 'http://127.0.0.1:8100/actuator/health/liveness'
Invoke-RestMethod 'http://127.0.0.1:8100/actuator/health/readiness'
```

### 4. 启动 Web 与桌面端

在仓库根目录的新终端启动 Web：

```powershell
pnpm dev:web
```

浏览器访问 [http://127.0.0.1:18173](http://127.0.0.1:18173)。保持 Web 运行，在另一个终端启动桌面端：

```powershell
pnpm dev:desktop
```

| 服务 | 默认地址 |
| --- | --- |
| 后端 | `http://127.0.0.1:8100` |
| Web 开发服务器 | `http://127.0.0.1:18173` |
| Web 构建预览 | `http://127.0.0.1:18174` |
| Electron | 加载 Web 地址，不额外监听端口 |

Vite 将 `/api` 代理至后端。修改 `YUMPOO_SERVER_PORT` 后，需同步修改 [vite.config.ts](frontend/web-app/vite.config.ts) 中的代理目标。

## 常用配置

后端配置基线见 [application.yml](backend/src/main/resources/application.yml)，生产值通过外部配置与环境变量注入。

| 配置 | 用途 |
| --- | --- |
| `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` | 应用数据库连接 |
| `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD` | 数据库迁移连接；共享与生产环境使用独立迁移账号 |
| `SPRING_PROFILES_ACTIVE` | 本地使用 `local`，生产使用 `prod` |
| `YUMPOO_SERVER_PORT` | 后端端口，默认 `8100` |
| `YUMPOO_LOCAL_AUTH_ENABLED` | 本地免登录身份开关，默认关闭 |
| `YUMPOO_SESSION_CURRENT_KEY_VERSION`、`YUMPOO_SESSION_CURRENT_KEY` | 生产会话密钥版本与 Base64 密钥；解码后至少 32 字节 |
| `YUMPOO_WEB_URL` | Electron 加载地址；开发限定本机 HTTP，发行包要求 HTTPS |
| `YUMPOO_DEFENDER_EXECUTABLE` | Microsoft Defender `MpCmdRun.exe` 路径，生产必填 |

本地附件和上传临时目录默认使用 `out/attachments`、`out/upload-temp`，可分别通过 `YUMPOO_ATTACHMENT_ROOT`、`YUMPOO_UPLOAD_TEMP_ROOT` 配置。未配置 Defender 时后端仍可在本地启动，但附件不会通过扫描并变为可下载状态。开发日志默认写入 `out/logs/yumpoo-server.log`，可通过 `YUMPOO_LOG_FILE` 调整。

生产目录以部署模板中的路径为准，配置与 Secret 文件需保存在部署机受保护的目录内。

## 构建与验证

```powershell
# 构建 Web、共享包与 Electron 代码
pnpm build

# 构建后端 JAR 并执行单元、集成与架构测试（需要 Docker）
.\backend\mvnw.cmd -f backend/pom.xml clean verify

# 预览 Web 构建产物
pnpm --filter @yumpoo/web-app preview
```

主要产物为 `backend/target/yumpoo-server.jar`、`frontend/web-app/dist/` 和 `desktop/desktop-shell/dist/`。

| 命令 | 用途 |
| --- | --- |
| `pnpm run ci` | 按顺序运行全部本机可用 CI 阶段；Windows x64 包含 Windows 交付验证 |
| `pnpm ci:static` | 文档、契约、代码规范、类型、Node 工作区测试与构建 |
| `pnpm ci:backend` | 后端完整回归与受限堆内存的附件流式探针 |
| `pnpm smoke:desktop` | 启动 Electron 验证实际 SPA 加载 |
| `pnpm doc-sync` | 校验 Markdown 链接、Agent Note 结构与冻结档案完整性 |

统一验证入口使用 **`pnpm run ci`**，避免与 pnpm 自带的 `ci` 命令混淆。完整链路与失败定位见 [CI 与 PR](.github/CI.md)。

REST 契约以 [yumpoo-v1.yaml](contracts/openapi/yumpoo-v1.yaml) 为唯一来源。修改后执行 `pnpm generate:api-client`，通过 `pnpm check:openapi` 校验；事件契约使用 `pnpm validate:event-contracts`。`packages/api-client/src/generated/` 由工具生成。

## Windows 部署

部署目标需要 Windows x64、JDK 21、PostgreSQL 17、Nginx / HTTPS 和 Microsoft Defender。服务拓扑为：

```text
浏览器 / Electron → Nginx HTTPS :443
                    ├─ /api/ → Spring Boot 127.0.0.1:8100
                    └─ /     → 静态 SPA 127.0.0.1:18173
```

### 生成服务器与桌面包

在 Windows x64 完成上述构建与验证后执行：

```powershell
pnpm package:m1-15:win
pnpm verify:m1-15:package

pnpm --filter @yumpoo/desktop-shell package:win
```

服务器包位于 `out/m1-15/yumpoo-windows-server-m1-15.zip`，包含当前构建的 JAR、Web、迁移和部署资产，并附带 SHA-256 与产物清单；桌面包位于 `desktop/desktop-shell/out/Yumpoo Desktop-win32-x64/`。现有打包命令沿用里程碑编号。

### 配置与启动

1. 使用 [数据库初始化脚本](deployment/windows/database/initialize-database.sql) 创建 `yumpoo` 数据库及 `yumpoo_app`、`yumpoo_migrator` 两个独立账号，设置各自密码。
2. 将服务器包解压到版本目录，准备 `C:/ProgramData/Yumpoo/` 下的配置、Secret、附件、临时目录与日志目录，并设置服务账号权限。
3. 按目标机修改 [生产配置模板](deployment/windows/config/application-prod.yml) 和 [Nginx 模板](deployment/windows/nginx/yumpoo-wecom.conf)：数据库端口、公开域名、证书和文件路径均需匹配实际环境。
4. 注入数据库密码、会话密钥、Defender 路径及独立的企业微信 OAuth / 通讯录 / 成员资料 Secret，配置可信 IP 与回调地址。首次部署按运行手册执行停服身份引导，创建初始管理员。

准备完成后，在部署机前台启动后端（按实际版本目录调整 JAR 路径）：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/application-secrets.yml'

java '-Dfile.encoding=UTF-8' -jar 'C:/Program Files/Yumpoo/releases/current/server/yumpoo-server.jar'
```

启动 Nginx 后，从 HTTPS 域名访问平台；发行桌面端需在启动环境中设置 `YUMPOO_WEB_URL=https://<实际域名>`。生产提供静态 Web 构建产物，预览命令用于本地检查。

首次身份引导、备份、验收与回退步骤见 [Windows 运行手册](deployment/windows/RUNBOOK.md)。目标环境的企业微信、HTTPS、Defender 和恢复验收需实际执行，构建或 CI 通过不能替代这些检查。

## 开发约定

- 从最新 `dev` 创建独立任务分支，通过目标为 `dev` 的 PR 集成，并完成代码审查与 CI。
- 文件统一使用 UTF-8；依赖更新同步维护根目录唯一的 `pnpm-lock.yaml`。
- 后端模块按 `api / application / domain / infrastructure` 分层；Web 通过生成客户端访问 API，通过共享类型契约调用受限桌面桥。
- 项目规则见 [AGENTS.md](AGENTS.md)，长期决策见 [Agent Notes](.agents/notes/README.md)。

## 许可证

[MIT License](LICENSE)
