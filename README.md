# YumpooPlatform

## M0-17 数据库与附件成套备份/隔离恢复原型

M0-17 以测试与验证工具交付可重复的本地恢复闭环，不新增生产备份命令、业务表或 HTTP API。门禁使用两个独立的 `postgres:17.10-alpine` Testcontainers 实例，在源实例内执行 `pg_dump -Fc`，完整验证备份集后才向全新目标实例执行 `pg_restore`。合成引用表只存在于测试数据库；附件复用 M0-14 的 SHA-256 内容寻址目录并包含一个只报告、不删除的孤儿样本。

```powershell
pnpm verify:m0-17
```

备份集同时包含 PostgreSQL custom dump、正式附件 blob、普通配置恢复样例和不含 Secret 值的恢复描述。`manifest.json` 最后写入并精确覆盖全部载荷，记录应用/PostgreSQL/Flyway 版本、公司时区、源码提交、文件角色、字节数和 SHA-256；绝对路径、路径穿越、反斜杠、符号链接、Windows 大小写碰撞、缺件、额外文件和篡改都会失败关闭。恢复要求目标数据库无 `yumpoo` schema、附件目录为空，完成后复核 Flyway 版本、合成引用、大小、哈希和实际可读字节。

`evidence/m0-17` 只跟踪 manifest、retention plan 和 verification report 的严格 JSON Schema 与合成示例。每次门禁的新鲜备份集和 `PASS` 报告写入忽略目录 `out/m0-17`，不提交环境绑定快照。保留规划仅 dry-run 选择 14 daily、8 weekly、6 monthly，支持多标签与 legal hold，绝不删除文件。外部介质、Windows 计划任务、失败告警、真实业务恢复以及 RPO 24 小时/RTO 4 小时演练仍属于 M5/M6。

## M0-16 Windows 部署资产与本地运行门禁

M0-16 交付可机审的 Windows Server 2022 x64 开发部署资产，以及完整的本地构建、运行和发布包复核门禁。它不会真实安装 IIS、注册 WinSW 服务、修改 ACL/防火墙，也不会把目标服务器证据写成 `PASS`。目标机验收和签名收据留给 M5-14/M6；当前 `evidence/m0-16/live-verification.json` 必须严格保持 `NOT_RUN`。

```powershell
pnpm verify:m0-16
```

门禁依次检查 Windows x64、Java 21、Docker 与工具链，校验 `deployment/windows` 和 M0-16 证据，执行完整 `verify:m0-15` 回归，组装发布 ZIP，点名启动 packaged JAR，最后重新解包复核白名单、逐文件哈希和 ZIP 哈希。输出位于：

- `out/m0-16/yumpoo-windows-m0-16.zip`
- `out/m0-16/yumpoo-windows-m0-16.zip.sha256`

ZIP 包含后端 JAR、Vite 生产构建、普通配置与 Secret 占位模板、IIS/WinSW 模板、供应链锁定信息和运行清单；不包含真实 Secret、WinSW 二进制、source map 或源码绝对路径。`artifact-manifest.json` 列出除自身以外的全部包内载荷，路径排序并记录字节数与 SHA-256。

生产 profile 固定只监听 `127.0.0.1`，关闭 forwarded-header 解析并启用 45 秒 graceful shutdown。readiness 同时反映数据库和附件/临时上传/日志目录写入状态；这些依赖故障时 readiness 为 503/DOWN，而 liveness 保持 200/UP。Windows 参数化模板和 dry-run 清单见 `deployment/windows/RUNBOOK.md`。

## M0 验收口径

M0 将本地/CI 可重复的开发门禁与依赖外部条件的环境门禁分开：`pnpm verify:m0-*` 证明协议、持久化、安全边界、构建与证据格式；真实企业微信 OAuth、公司 HTTPS、真实 Defender/NTFS、干净 Windows Server、IIS、服务账号 ACL、仅 443 和整机重启必须在对应环境中另行证明。未执行时 live evidence 保持 `NOT_RUN`，不阻塞本地开发，也绝不等于 `PASS`。

M0 不实现正式企业微信扫码登录，不创建正式 User、ExternalIdentity、LoginSession 或可续期会话。正式 Web 身份与会话能力属于 M1，正式 Electron 认证与桌面会话属于 M4；两者的真实企微/公司 HTTPS E2E 与目标 Windows Server 部署恢复证据在 M5/M6 环境门禁完成。

## M0-15 Electron 浏览器交接与安全壳验证

M0-15 的开发门禁验证 Electron 复用唯一远程 SPA、系统浏览器交接协议、PKCE、一次性 handoff、自定义协议处理和最小安全壳，不提前交付 M4 的正式桌面会话，也不要求真实企微登录。真实系统浏览器企微 OAuth、公司 HTTPS SPA 与 `yumpoo://` 的端到端证据属于 M4-14/M6 环境门禁。后端诊断能力默认不存在；只有 profile 列表包含 `m0-15-live` 且 `YUMPOO_M015_WECOM_ENABLED` 严格等于 `true` 时才注册以下非 OpenAPI 路径：

- `GET /_m0/m0-15/electron/auth/authorize`，接收 `state`、`codeChallenge` 和固定的 `codeChallengeMethod=S256`。
- `GET /_m0/m0-15/wecom/callback`，完成企微成员检查后跳转 `yumpoo://auth/callback`。
- `POST /_m0/m0-15/electron/auth/exchange`，只接收 `code`、`state`、`codeVerifier` 并返回短期 HMAC 签名的脱敏收据。

真实后端从受控外部环境读取以下配置；不得把真实值写入仓库、日志、命令输出或工单：

- `YUMPOO_M015_WECOM_CORP_ID`
- `YUMPOO_M015_WECOM_AGENT_ID`
- `YUMPOO_M015_WECOM_APP_SECRET`
- `YUMPOO_M015_WECOM_CALLBACK_URI`（固定、不带 query/fragment 的同源 HTTPS callback）
- `YUMPOO_M015_WECOM_ALLOWED_MEMBER_IDS`
- `YUMPOO_M015_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种字符，独立于企微 Secret）
- `YUMPOO_WEB_URL`（packaged app 加载的唯一公司 HTTPS SPA）

自动门禁从仓库根目录运行：

```powershell
pnpm verify:m0-15
```

该命令先严格校验 `evidence/m0-15` 的 Schema、示例和当前证据，再调用 `package:m0-15:win` 生成 Windows x64 packaged app、扫描 ASAR 白名单，并为可运行目录中的每个文件生成和复核 SHA-256 manifest，最后串联最新的 `verify:m0-14`；不会从 M0-12 重复建立另一条回归链。普通实现和 PR 允许 `live-verification.json` 保持 `NOT_RUN`。

真实验证只接受一次真实系统浏览器企微登录、公司 HTTPS SPA、packaged app 和 `yumpoo://auth/callback` 共同产生的短期收据。除上述后端变量外，还须由受控 live harness 提供：

- `YUMPOO_M015_LIVE_BASE_URL`（与 callback、`YUMPOO_WEB_URL` 同源的 HTTPS origin）
- `YUMPOO_M015_AUTH_RECEIPT_PATH`（本次 exchange 原始响应的短期文件）
- `YUMPOO_M015_DESKTOP_RECEIPT_PATH`（绑定本次认证收据与构建 manifest 的 HMAC 桌面收据）
- `YUMPOO_M015_BUILD_MANIFEST_PATH`（本次 packaged app 的 manifest）

上述路径必须是绝对路径。证据 HMAC 密钥只进入受控后端/live harness，绝不传入 Electron 应用。准备完成后运行：

```powershell
pnpm verify:m0-15:live
```

当前 live runner 是安全 preflight 与收据验证入口，不模拟企微登录、不代签桌面收据。它只在 Windows x64、双门禁、同源 HTTPS、近期后端签名认证收据、近期域分离桌面收据和实际 manifest 摘要全部匹配时，才原子更新证据为 `PASS`；失败时不改原证据，并始终删除两份短期收据。最终证据只保存 Windows/架构、Electron 版本、固定协议回调、manifest SHA-256 和布尔检查，不保存 code、state、verifier、身份指纹、requestId、签名、路径、Cookie、token 或 Secret。自动 live harness 尚未串联或真实流程未执行时必须保持 `NOT_RUN`，不得手工改成 `PASS`。

本切片不发布正式 `/api/v1/electron/auth/*`、不创建 User、ExternalIdentity、LoginSession 或可续期桌面凭据，也不实现 Windows 凭据存储、通知流、托盘业务、版本阻断、安装器、自动更新或离线业务能力；这些仍属于 M1/M4 或 M0 后续退出验证。

## M0-14 安全附件工程验证

M0-14 冻结的是可复用的文件安全技术核心，不是正式附件业务功能。生产代码提供固定缓冲流式接收、100 MiB（`104857600` bytes）硬上限、增量 SHA-256、文件名净化、Apache Tika 内容识别、ZIP/OOXML 区分、恶意内容扫描端口、Microsoft Defender `MpCmdRun` 适配器，以及隔离区到同卷内容寻址目录的 `ATOMIC_MOVE`。只有服务端识别类型与扩展名、声明 MIME 一致且扫描结果明确为 `CLEAN` 时，内容才可转为 `AVAILABLE`；超限、类型不符、威胁、扫描超时/未知结果和中断均失败关闭。

本切片不新增生产 Attachment Flyway 表，也不发布正式 `/api/v1/attachments` OpenAPI path。PostgreSQL 表、父对象授权桩和 `/api/v1/__test/m0-14/attachments` 都只存在于测试源码，用来证明短事务、异步扫描、回滚孤儿安全、下载前再次授权和无权隐藏 404；正式元数据、业务授权、配额、删除/清理、调度及 Activity/Outbox 留给 M2。公共契约仅补齐既有 `FILE_TOO_LARGE`（413）与 `FILE_TYPE_NOT_ALLOWED`（415）的 golden response。

```powershell
pnpm verify:m0-14
```

该门禁会先校验证据文件，再以 `-Xmx96m` 点名执行 100 MiB 懒生成流探针，最后串联 `verify:m0-13` 的完整 OpenAPI、后端 PostgreSQL/Testcontainers、Node、桌面与前端回归。Docker 不可用时真实 PostgreSQL 验收会失败，不会回退到 H2。普通开发与 PR 允许 `evidence/m0-14/live-verification.json` 保持 `NOT_RUN`；真实 Defender/NTFS 证据在具备受控 Windows 环境后补验，并在 M5/M6 发布门禁前完成。

真实环境验证必须确认允许使用 EICAR 测试串，并从外部注入以下变量；不要把路径、密钥或扫描器输出提交到仓库或工单：

- `YUMPOO_M014_LIVE_ENABLED=true`
- `YUMPOO_M014_ALLOW_EICAR=true`
- `YUMPOO_M014_LIVE_ROOT`（已存在、空间充足的 NTFS 目录）
- `YUMPOO_M014_DEFENDER_EXECUTABLE`（`MpCmdRun.exe` 的绝对路径）
- `YUMPOO_M014_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节且至少 8 种字符）

```powershell
pnpm verify:m0-14:live
```

live runner 只在配置目录下创建一次性 `m0-14-live-*` 子目录；它验证近上限干净样本、EICAR 失败关闭、NTFS 同卷原子移动和中断清理，校验短期 HMAC 收据后才原子更新脱敏证据，并始终删除短期收据。Defender 退出码 `2` 同时可能表示威胁或扫描错误，因此适配器保守映射为 `INDETERMINATE`，绝不解析本地化控制台文本来猜测“干净”。

YumpooPlatform 一期采用单部署的模块化单体后端、共享 Vue SPA，以及只加载同一在线 SPA 的 Electron 桌面壳。

## M0-13 企微通讯录安全验证骨架

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
evidence/m0-13/              通讯录验证的 Schema、示例与脱敏证据
```

本仓库使用 Java 21、Maven Wrapper 3.9.9、Node.js 24.14.0 和 pnpm 11.16.0。Node 工作区只有根目录一份 `pnpm-lock.yaml`，所有声明依赖均锁定精确版本。后端数据库基线是 PostgreSQL 17.10、Spring JDBC 和 Flyway，业务对象统一进入 `yumpoo` schema。

## 安装与联合验证

```powershell
pnpm install --frozen-lockfile
pnpm verify:m0-13
pnpm smoke:desktop
```

`verify:m0-13` 先校验 M0-13 真实验证证据的 Schema、示例和当前文件，再串联 `verify:m0-12` 的全部门禁：M0-12 证据、事件目录、JSON Schema 与正反样例、OpenAPI、生成客户端及生成物漂移、后端 Maven Verify，以及 Node 工作区的 Lint、类型检查、边界负向测试、单元测试和生产构建。后端 Verify 会通过 Testcontainers 启动 `postgres:17.10-alpine`；Docker 不可用时直接失败，不使用 H2 或跳过真实库验收。`smoke:desktop` 在随机回环端口启动已构建的 Vue SPA，并让隐藏的 Electron 窗口完成一次真实加载后正常退出。

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

## M0-12 企微环境验证（延期门禁）

M0-12 诊断路由默认不存在。只有同时设置 `SPRING_PROFILES_ACTIVE=m0-12-live` 和 `YUMPOO_M012_WECOM_ENABLED=true` 时，后端才注册以下路径：

普通开发与 PR 只要求 `pnpm verify:m0-12` 通过。它使用可控测试边界验证 OAuth 参数、corp/member 映射、state/nonce、并发消费、伪造/重放拒绝、供应商失败映射和脱敏；不要求真实成员授权或扫码、公开域名和真实 HTTPS 回调。以下 live 流程在受控联调环境具备后执行，最迟在 M6 候选版本冻结前完成；此前证据保持 `NOT_RUN`。

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

## M0-13 企微通讯录真实验证

M0-13 live runner 采用双重门禁。只有 profile 列表包含 `m0-13-live` 且 `YUMPOO_M013_WECOM_ENABLED` 严格为 `true` 时才允许运行。请只在已把 Java 直连企微时的出口公网 IP 加入企微可信 IP、且服务账号可读取受控环境变量的非生产测试环境中注入以下五项。`HTTP_PROXY/HTTPS_PROXY` 环境变量不会自动改变本 live harness 的 Java 出口；通过代理查询到的公网 IP 不能代替实际直连 IP。

- `SPRING_PROFILES_ACTIVE`（包含 `m0-13-live`）
- `YUMPOO_M013_WECOM_ENABLED=true`
- `YUMPOO_M013_WECOM_CORP_ID`
- `YUMPOO_M013_WECOM_DIRECTORY_SECRET`（通讯录同步 Secret，不得复用 OAuth 应用 Secret 的配置名）
- `YUMPOO_M013_EVIDENCE_HMAC_KEY`（至少 32 个 UTF-8 字节、至少 8 种 Unicode code point，不含 `change-me`、`changeme`、`placeholder`、`password` 或 `secret-key`，且不得复用通讯录 Secret）

不要把上述真实值写入 PowerShell 历史、仓库、工单、日志或聊天。配置完成后从仓库根目录执行：

```powershell
pnpm verify:m0-13:live
```

Node runner 会先删除任何遗留收据，再精确执行 Maven 测试类 `M013WeComDirectoryLiveVerification`。为保证 live 验证不连接业务数据库，Maven 子进程会继承 Java、Docker、PATH 和五项 M0-13 配置，但显式移除遗留的 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` 与 `SPRING_FLYWAY_URL/USER/PASSWORD`。Java 在任何 probe 对账前先完成两次 `limit=1` 的真实窄页扫描和一次 `limit=10000` 的宽页扫描：两次窄页都必须观察真实非空游标，三次都必须以供应商省略终止游标结束，且企业、快照和成员 HMAC 集合完全一致。随后才验证重跑幂等、分页失败保护、成员级部分失败、合成离职/返聘和脱敏限制，并在 `backend/target/m0-13-live-receipt.json` 写入一次短期 HMAC 签名收据。收据签名输入固定为 UTF-8 `receipt\0` 域前缀加 canonical 正文，不能与 collector 的 corp/member/snapshot HMAC 消息空间复用。Node runner 校验严格字段白名单、运行时间窗、全部布尔检查和签名后，才原子地把 `evidence/m0-13/live-verification.json` 更新为 `PASS`；无论成功或失败都会删除收据，任何失败都不会覆盖原证据。

M0-13 证据只保存企业与目录快照的不可逆 HMAC、运行时间、执行时 M0-12 证据状态和布尔检查，不保存人数、页数、游标、原始成员 ID、个人资料、Secret、token、签名或完整供应商响应。`providerPaginationObserved` 证明窄页真实分页，`providerTerminalCursorOmissionConfirmed` 证明三份脱敏快照已交叉确认供应商省略终止游标。M0-12 状态只如实记录为 `NOT_RUN` 或 `PASS`，不阻断 M0-13 的独立通讯录验证；未完成真实验证时 M0-13 证据必须保持 `NOT_RUN`。人工诊断中输出的通讯录或原始 userid 不得写入仓库、证据、日志或提交说明。

`externalLimitsRecorded` 固定验证以下企微只读契约，不把运行时数量写入证据：通讯录 ID 拉取使用 `POST /cgi-bin/user/list_id`，请求 `limit` 只能在 1～10000；官方契约以空 `next_cursor` 表示结束，但本次真实企业调用观察到终止页直接省略该字段。主 `DirectorySnapshotCollector` 对缺失/null 游标始终返回 `Incomplete(MISSING_CURSOR)`，只有 M0-13 live 在已经观察真实分页、两次窄页与一次宽页 HMAC 快照完全一致时才把它作为测试专用候选快照，M1 正式同步必须重新冻结生产语义。该接口必须使用通讯录同步 Secret，且此 Secret 只读取成员 ID，不读取姓名、手机、邮箱等成员资料。`/cgi-bin/gettoken` 正常返回 7200 秒有效期，客户端必须缓存并提前刷新，不能逐页换 token。错误 `-1`、`45009` 可退避重试；`40001`、`48002`、`60020` 是凭据、权限或可信 IP 配置失败，不得盲目重试；`40014`、`42001` 只允许失效缓存并刷新 token 后重试一次。冻结依据为企微官方的[获取成员 ID 列表](https://developer.work.weixin.qq.com/document/path/96067)、[通讯录接口调整说明](https://developer.work.weixin.qq.com/document/path/96079)、[获取 access_token](https://developer.work.weixin.qq.com/document/path/91039)和[全局错误码](https://developer.work.weixin.qq.com/document/path/90313)。

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

共享和生产环境还必须设置独立迁移账号的 `SPRING_FLYWAY_URL`、`SPRING_FLYWAY_USER`、`SPRING_FLYWAY_PASSWORD`。所有密码都从外部配置注入，不进入仓库。M0-12 与 M0-13 都不新增正式生产业务端点；未启用诊断双重门禁时仅对外提供：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

## 架构边界

- 后端 13 个一级模块统一采用 `api/application/domain/infrastructure` 四层；ArchUnit 对层级方向、允许依赖矩阵、跨模块内部实现访问和循环依赖执行硬门禁。
- Web/renderer 不得导入 Node、Electron 或 desktop-shell 实现；可运行时依赖浏览器 Fetch 边界的 `@yumpoo/api-client`，只能以 type-only 方式读取 `@yumpoo/preload-contract`。
- OpenAPI 是请求、响应、错误、分页和客户端生成的唯一契约源；生成目录禁止手工修改，漂移由验证脚本阻止。
- preload 仅在唯一入口通过三个固定认证通道包装 `ipcRenderer`，不暴露原始 IPC 或 Node built-in；Renderer 只看到冻结的 `window.yumpooDesktop` 最小桥。
- Electron main/preload/Web 分离编译；新窗口、权限请求和跨源导航默认拒绝。

## 当前与后续范围

M0-11 在既有幂等事务底座上增加稳定内部事件信封、`outbox_event`、`outbox_consumer_receipt` 和可配置 dispatcher。`TransactionalEventPort` 只能加入现有事务，业务失败时事实、幂等记录与事件一并回滚；HTTP 根请求的 `requestId` 同时成为 `correlationId`，消费与派生事件继续继承关联链路。内部事件契约位于 `contracts/events`，本切片只登记测试探针事件，不增加正式 HTTP 路径或 OpenAPI operation。

M0-12 增加可复用的企微身份网关、持久化 OAuth attempt 和仅用于真实验证的双门禁诊断路由。state 与浏览器 nonce 都只以哈希持久化，attempt 在访问企微前原子消费；成功只返回 HMAC 签名脱敏收据，不创建 User、ExternalIdentity 或 LoginSession。`DEPENDENCY_UNAVAILABLE` 是公共 503 契约，但诊断路由本身不进入正式 OpenAPI paths。

M0-13 增加通讯录读取适配器和仅供验证的同步对账探针。真实调用验证受可信 IP 保护的通讯录凭据与供应商分页行为；可重复的本地场景验证中途失败不触发离职对账、单成员失败可诊断、重跑不重复建立身份，以及同一外部标识的合成离职/返聘复用。该切片不创建正式 Company、User、ExternalIdentity、DirectorySyncRun 或管理 API，正式同步批次、游标、暂存和会话撤销仍由 M1 实现。

worker 默认每秒轮询，批量 50、并发 2、租约 5 分钟。领取覆盖到期的 `PENDING/RETRY` 与租约过期的 `PROCESSING`，并以 owner + token 防止旧 worker 回写；低版本未完成或 `DEAD` 会阻塞同聚合高版本。每个消费者的数据库效果与 receipt 在独立事务中提交，多消费者重试会跳过已完成者；五档退避后第六次失败进入 `DEAD`。控制台使用 Spring Boot 内建 Logstash JSON 日志，并在请求和消费边界写入受控关联字段。

正式身份绑定与会话、Security Audit、Activity 投影、通知投递、人工重排、监控告警、Outbox 清理和管理页面仍留给后续切片。完成 M0-13 也不代表完整 M0 里程碑退出。
