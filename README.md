# YumpooPlatform

## M1-08 平台/企业角色与授权策略

M1-08 新增只读的平台角色底座。`platform_role_assignment` 保存 `COMPANY_ADMIN` 与 `APP_MANAGER` 的作用域、授予/撤销事实和历史版本；`COMPANY_MEMBER` 继续由 `ACTIVE + ENABLED` User 派生，不入角色表。角色表不预置管理员，本切片也不开放授予/撤销命令、REST 或管理页面。正式写入口由后续 M1-09 在同一事务内递增 `authorization_version` 并撤销会话后再开放，业务代码和 fixture 之外不得直接写表。

会话认证成功后按 Company/User 查询有效角色，并把角色集合与当前 `authorizationVersion` 一起固化进 `CurrentActor`。`/api/v1/auth/me` 固定按 `COMPANY_MEMBER → COMPANY_ADMIN → APP_MANAGER` 返回，Spring authorities 使用同一份快照。通用授权 guard 将可见拒绝映射为 403，将需隐藏的拒绝映射为 404。

`catalog.api.ProjectAccessSnapshotQuery` 只冻结 M2 所需的最小只读契约，本轮没有 Project、membership、owner 表或生产实现。后续实现必须在 SQL 中同时按 Company 与可见范围过滤，禁止先无范围读取再在 Java 中隐藏。当前纯策略规定：成员可正常读写；非成员 `COMPANY_ADMIN` 只读、普通写 403；仅 `APP_MANAGER` 与普通非成员隐藏 404；角色兼任按能力并集处理；跨 Company 始终隐藏。

```powershell
pnpm verify:m1-08
```

完整验证需要 Docker Desktop Linux engine，以运行 PostgreSQL 17/Testcontainers 集成测试；无 Docker 时只能执行 `cd backend; .\mvnw.cmd -DskipITs test` 与 `pnpm verify:node`，不得视为完整通过。

## M1-07 账号启停与会话撤销闭环

M1-07 交付后端内部的 `AccountStatusUseCase`：账号状态与就业状态严格独立，禁用和启用命令都要求预期行版本、持久化幂等键、请求哈希和 1～160 字符的原因引用。真实状态迁移会同时递增 `row_version` 与 `authorization_version`；禁用记录操作者和原因，启用保留最近一次禁用事实，LEFT 用户允许启用账号但仍不能登录。同键同请求重放已保存结果，同键异参、陈旧版本、重复状态和跨企业访问分别按统一语义拒绝。

完整目录同步将成员置为 LEFT、手工账号禁用或启用时，会在同一事务中撤销该用户所有尚未逻辑过期的 Web/Electron 活动会话。已过期但尚未落终态的会话不会被重分类，因而继续返回 401；以 `EMPLOYMENT_LEFT` 或 `ACCOUNT_DISABLED` 撤销且仍在保留期内的旧凭据返回 403。返聘或重新启用不会恢复任何旧会话，请求已通过过滤器后发生状态变化时，`CurrentActor` 的数据库复核仍会在业务代码执行前拒绝请求。

事件契约新增 `identity.user_account_disabled` v1、`identity.user_account_enabled` v1 和用户级 `identity.user_sessions_revoked` v2；logout 使用的 v1 保持兼容。公共 payload 不包含自由文本原因，操作者与原因引用只进入 `ADMIN_OVERRIDE` actor envelope。本切片不新增数据库迁移、管理端 REST/OpenAPI、生成客户端或页面；角色、最后管理员保护、近期认证、Security Audit 与管理页面继续由 M1-08～M1-11 交付。

```powershell
pnpm verify:m1-07
```

## M1-05 通讯录部分失败、对账、离职与返聘

M1-05 在 M1-04 全量同步批次上增加成员级失败隔离和完整快照对账。完整扫描后的单成员资料或写入失败会记录稳定错误并继续处理其余成员，批次终态为 `PARTIALLY_SUCCEEDED`；任何部分失败都不会执行缺失成员离职对账。ID 扫描、部门字典、共享凭据、租约和持久化等全局故障仍将批次置为 `FAILED`。同一 trigger key 永久重放原批次，修复问题后必须以新 trigger key 发起新的全量同步。

只有扫描完整且全部发现成员成功时，最终事务才会把本次未出现的 ACTIVE WECOM 身份原子标记为 `LEFT`。若本地已有 ACTIVE 成员，供应商返回空目录会以 `DIRECTORY_EMPTY_SNAPSHOT_REJECTED` 失败关闭。相同 external ID 再次出现时复用原 User 并记为 `RETURNED`，保留禁用和最近离职事实，同时递增授权版本。事件契约新增 `identity.directory_sync_completed` v2 及就业 LEFT/RETURNED v1，payload 不包含姓名、联系方式或 external ID。

```powershell
pnpm verify:m1-05
```

该入口复核既有 M1-04 live evidence，随后执行后端 `clean verify` 和完整 Node 门禁。本切片仍为纯后端内部用例，不新增 REST/OpenAPI、页面、调度或企微离职回调；不批量修改 `login_session`，会话撤销及完整 401/403 语义留给 M1-07。

## M1-04 通讯录同步批次与全量导入

M1-04 交付纯后端的 `DirectorySyncUseCase`：以 trigger key 幂等创建同步批次，按 Company 互斥并使用 5 分钟可续租约隔离旧 worker。Flyway `V9` 创建长期批次/成员结果和 RUNNING 期资料暂存；终态会删除暂存、清空原始游标与租约，只保留 external user ID、profile hash、动作、计数和稳定错误码。该切片不增加 REST/OpenAPI、页面、定时调度、离职/返聘、成员级重试或会话撤销。

目录 ID 与成员资料使用两个独立企微 Secret。显式空 `next_cursor` 可单次确认完成；供应商省略终止游标时必须重复完整扫描，成员集合、页数和逐页摘要一致才继续。全部资料读取完成前不会修改 User；部门名按数字部门 ID 排序后以顿号汇总，手机号/邮箱缺失会保留旧值，显式空值才清除。生命周期通过 `identity.directory_sync_started/completed/failed` v1 事件发布，payload 不含个人资料、原始游标或凭据。

```powershell
pnpm verify:m1-04
```

该入口先校验 `evidence/m1-04`，再执行后端 `clean verify` 与完整 Node 门禁；PostgreSQL 集成测试要求 Docker Desktop Linux engine 可用。真实企微验证是独立的非自动门禁，证据默认保持 `ENV_PENDING`。准备两类受控 Secret、独立的至少 32 字节 HMAC 密钥，并启用 `m1-04-live` profile 与 `YUMPOO_M104_WECOM_ENABLED=true` 后，运行 `pnpm verify:m1-04:live`；runner 只提交 HMAC 指纹、布尔检查和经短期签名收据验证的 PASS 事实。

## M1-03 会话与安全底座

M1-03 交付 PostgreSQL 不透明 Web 会话、User 授权版本、Spring Security 7 安全链和数据库绑定的 Cookie/CSRF 契约。Session 与 CSRF 原文只在签发时返回一次，数据库仅保存用途隔离的 HMAC-SHA-256 指纹；会话采用 8 小时空闲、7 天绝对过期和绝对到期后 24 小时的撤销事实保留期。

`/api/v1/**` 默认要求 `__Host-yumpoo-session` 认证，写请求还需以 `X-XSRF-TOKEN` 回传可读的 `__Host-yumpoo-csrf` Cookie。两个 Cookie 均固定 `Secure`、`SameSite=Lax`、`Path=/` 且无 Domain，Session 额外启用 `HttpOnly`。本切片不新增 callback、logout、`/me` 或正式登录页面。

```powershell
pnpm verify:m1-03
```

该入口依次执行后端 `clean verify`（含 PostgreSQL 17/Flyway、会话并发、CSRF 与安全链集成测试）及完整 Node 工作区门禁。

## M1-02 User 与 ExternalIdentity 底座

M1-02 在 `identityaccess` 模块建立正式 `identity_user` 与 `external_identity` 数据模型。WECOM 外部成员标识在 Company 内唯一，且一期与 User 严格一对一；姓名、邮箱和手机号仅为当前目录资料，变化时复用原 User，不参与身份合并。就业状态与账号状态分别持久化，目录资料刷新不会隐式改变任一状态。

模块内部的 `DirectoryMemberProvisioningService` 使用唯一 Company 配置和 PostgreSQL 事务级 advisory lock 串行化同一外部身份的建立/刷新，避免并发首次同步产生重复绑定或孤儿 User。该切片不新增 REST/OpenAPI、前端、同步批次、会话、账号治理、角色或事件发布。

```powershell
pnpm verify:m1-02
```

该入口依次运行后端 `clean verify`（含 PostgreSQL 17/Flyway、并发建立、数据库约束及备份恢复验证）和完整 Node 工作区门禁。

## M1-01 Company 与工作日历底座

M1-01 在 `organization` 模块交付单 Company 与工作日历的后端底座。数据库迁移 `V6` 固定种子为 `Yumpoo`、`Asia/Shanghai`、周一周起始和 480 分钟默认工作日，并以数据库约束保证单 Company、日历日期唯一及工作日分钟语义。运行期只从数据库读取 Company 配置；缺失、非法 IANA 时区或非周一起始配置都会失败关闭。

跨模块只通过 `organization.api` 的只读查询契约取得 Company 配置和解析后的日历快照。缺省规则为周一至周五工作、周末休息，显式覆盖优先；日期计算与本地时刻解析不依赖服务器默认时区，并固定处理 DST 缺口和重叠。本步不新增 REST/OpenAPI、前端或日历管理命令。

```powershell
pnpm verify:m1-01
```

该入口依次运行后端 `clean verify`（含 PostgreSQL 17/Flyway、架构、日历边界与备份恢复验证）和完整 Node 工作区门禁。

## M0-18 最小 CI 与开发证据包

M0-18 只交付验证编排、GitHub Actions 门禁和开发证据治理，不新增业务 API、数据库迁移或前端 DTO。项目的完整验证链与生产等价运行时只覆盖 Windows x64，需要 Node.js 24.14.0、pnpm 11.16.0、Java 21、PowerShell，以及运行 Linux container 的 Docker；OpenAPI 默认从 `origin/dev` 提取历史契约，基线缺失、为空或不可解析时直接失败。

```powershell
pnpm install --frozen-lockfile
pnpm test:m0-18
pnpm verify:m0-18
```

也可按 CI 边界分别运行 `pnpm verify:m0-18:portable` 与 `pnpm verify:m0-18:windows`，但二者是 CI 分段入口，不能替代 Windows x64 上的 `pnpm verify:m0-18`。`M0 Portable Gate` 在 Ubuntu 24.04 只执行可移植门禁：OpenAPI 兼容性、Maven Verify、Flyway/Testcontainers、ArchUnit、Node 构建与测试、100 MiB 探针和备份恢复；它随后用逐文件大小与 SHA-256 manifest 交付已测试的 JAR/Web 字节，不声明 Linux 运行时或生产等价性。`M0 Windows x64 Gate` 必须等待 portable 成功，复核同一提交和 handoff 精确文件集后，在 Windows 2022 执行真实 Electron smoke、Electron Windows 打包、ASAR 白名单、M0-16 ZIP 组装及复核。packaged-JAR、回环监听、外部配置、目录/数据库故障语义与脱敏拒启 smoke 只由完整 Windows x64 入口执行。

最终开发证据写入忽略目录 `out/m0-18/evidence-pack` 并由 CI 作为 30 天 artifact 上传。报告以 `validationMode` 区分 `WINDOWS_X64_FULL` 与 `WINDOWS_X64_CI_STAGE`；FULL 报告还必须消费绑定当前提交与 JAR 摘要的 server-smoke receipt。CI 分段报告必须把 `serverSmoke` 记为 `NOT_RUN`、使用 `pnpm verify:m0-18:windows` 作为复现命令，并带上 `WINDOWS_FULL_CHAIN_NOT_RUN` 限制，绝不冒充完整验证。包内只允许 verification report、延期清单、portable handoff manifest、M0-15/M0-16 manifest、ZIP 摘要和 M0-17 三份安全元数据；JAR、ZIP、附件、dump、日志、测试 XML、绝对路径、环境变量和任何凭据均被拒绝。动态 `PASS` 报告绑定实际测试提交，不进入 Git。

`evidence/m0-18/deferred-acceptance.json` 与仓库内所有 live evidence 双向精确对账：当前 M0-12、M0-14、M0-15、M0-16 保持 `NOT_RUN`，M0-13 已 `PASS` 因而不得列入延期集合。真实企微 OAuth、扫码登录、系统浏览器交接与公司 HTTPS 统一在 M6-01 部署/发布环境门禁补验；Defender/NTFS、干净 Windows Server/IIS，以及 M0-17 的计划任务、异机复制、告警、Secret 恢复、真实 Schema 恢复、保留清理和 RPO/RTO 演练仍是 M5/M6 环境或运维门禁。M0 开发门禁通过不代表这些 live 验收已经完成。

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

M0 不实现正式企业微信扫码登录，不创建正式 User、ExternalIdentity、LoginSession 或可续期会话。正式 Web 身份与会话能力属于 M1，正式 Electron 认证与桌面会话属于 M4；两者在本地使用仅限 local/test 的受控身份提供者验证，真实企微 OAuth、扫码、鉴权与公司 HTTPS E2E 统一在 M6-01 部署/发布环境门禁完成。

## M0-15 Electron 浏览器交接与安全壳验证

M0-15 的开发门禁验证 Electron 复用唯一远程 SPA、系统浏览器交接协议、PKCE、一次性 handoff、自定义协议处理和最小安全壳，不提前交付 M4 的正式桌面会话，也不要求真实企微登录。真实系统浏览器企微 OAuth、公司 HTTPS SPA 与 `yumpoo://` 的端到端证据统一属于 M6-01 部署/发布环境门禁；M4-14 只验证受控身份提供者下的本地桌面语义。后端诊断能力默认不存在；只有 profile 列表包含 `m0-15-live` 且 `YUMPOO_M015_WECOM_ENABLED` 严格等于 `true` 时才注册以下非 OpenAPI 路径：

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

完整的数据库环境变量、端口表和启动顺序以 `docs/30-operations/local-development.md` 为本地开发基线。

先启动在线 SPA：

```powershell
pnpm dev:web
```

另开终端编译并启动桌面壳：

```powershell
pnpm dev:desktop
```

Web 开发服务器默认监听 `http://127.0.0.1:18173`，Vite Preview 默认监听 `http://127.0.0.1:18174`。Electron 开发模式复用同一个 Web SPA，默认加载 `http://127.0.0.1:18173`；`YUMPOO_WEB_URL` 可以覆盖地址。开发环境只接受 `localhost` 或 `127.0.0.1` 的 HTTP 地址，生产环境必须提供无用户名密码的 HTTPS 地址。

后端程序的安全默认值仍为 `127.0.0.1:8080`，本项目本地开发基线通过 `YUMPOO_SERVER_PORT=8100` 覆盖。运行后端还需通过环境变量提供应用数据库连接：

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
## M1-09 角色治理与管理员紧急恢复

M1-09 在后端新增 `APP_MANAGER` 与 `COMPANY_ADMIN` 的分页查询、授予和撤销应用端口，但不注册正式 HTTP/OpenAPI。角色写入要求操作者是同公司的可用 `APP_MANAGER`，会话授权版本仍为当前值，且企微登录签发时间不早于命令执行前 15 分钟；过期后必须重新登录。角色变化、目标用户授权版本递增、Web/Electron 会话以 `AUTHORIZATION_CHANGED` 撤销、幂等结果和 Outbox 事件处于同一事务。

公司级 `app_manager_governance_state` 既是一次性首管闩锁，也是所有角色变化、账号启停和目录离职/返聘的并发互斥点。主动撤销或禁用最后一名可用 `APP_MANAGER` 会被拒绝；企微目录离职不会被阻塞，人数从 1 降为 0 时产生持久缺失事件，administration 投影为 `GovernanceIssue`，恢复后保留已解决历史。

首管引导和 break-glass 仅通过默认关闭的非 Web 维护 Runner 执行，不提供匿名或回环 HTTP。Windows 使用方式见 `deployment/windows/RUNBOOK.md` 与 `deployment/windows/Invoke-AppManagerMaintenance.ps1`。Security Audit、失败审计和角色写 HTTP 仍属于 M1-10，管理页面属于后续切片。

从仓库根目录执行完整门禁：

```powershell
pnpm verify:m1-09
```

## M1-10 Security Audit 与身份治理 HTTP

M1-10 新增 append-only 的 `security_audit_event`，以 Company + fact key 去重，并提供 Company + requestId 的内部分页查询端口。登录、退出、批量会话撤销、目录同步/离职返聘、账号启停、角色变更、首管/break-glass 及 APP_MANAGER 缺失/恢复均写入最小脱敏审计摘要。高风险成功审计与 User、Session、Outbox 和幂等结果同事务；业务拒绝回滚后以独立事务记录 FAILED，审计不可写时失败关闭并返回安全的 `INTERNAL_ERROR`。

正式 HTTP 已开放治理快照、角色查询/授予/撤销和成员账号启停，写请求统一要求 Session、CSRF、`Idempotency-Key`、`If-Match`、15 分钟近期认证及 1～160 字符理由。TypeScript 客户端由 OpenAPI 生成；本切片不开放审计查询 HTTP，也不包含管理页面、CapabilityAssignment、哈希链或 WORM 导出。

```powershell
pnpm verify:m1-10
```
