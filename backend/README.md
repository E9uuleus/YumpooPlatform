# Yumpoo Server

## M1-13 身份基础阶段验收门禁

`identityaccess.api.PlatformRoleCommandPort` 是平台角色授予与撤销的稳定跨模块命令入口。公共命令要求企业、目标用户或分配、预期版本、操作者、幂等键、64 位小写 SHA-256 请求摘要和治理理由，返回不泄露 application 类型的角色变更回执；适配器继续复用既有最近认证、末位 `APP_MANAGER`、审计、Outbox、会话失效和幂等回放语义。

受控门禁夹具由 `M113FixtureRunner` 启动，只在 `local|test + m1-13-e2e + yumpoo.verification.m1-13.fixture-enabled=true` 下可用。任何 `prod` profile、身份事实非空、受控身份提供者关闭或夹具标识缺失都会拒绝启动；它不提供测试 Controller，也不直接 SQL 写业务表。所需环境变量为 `YUMPOO_M113_FIXTURE_ENABLED=true`、`YUMPOO_M113_BACKUP_MEMBER_ID`，以及既有受控登录的 Corp ID/Member ID 配置。

从仓库根目录运行：

```powershell
pnpm verify:m1-13
```

该入口要求 Docker Linux engine 可运行 `postgres:17.10-alpine`，并要求本机 8100、18174 端口空闲。它验证 PostgreSQL 权限矩阵后，以外部 JAR 和 Vite production preview 完成受控登录、Cookie/CSRF、角色边界、退出与自禁用链路，最终在忽略目录 `out/m1-13/verification-report.json` 生成绑定当前 HEAD 的脱敏报告。Docker 或端口条件不满足时必须失败，不能用单元测试结果替代完整门禁。

## M1-08 平台角色读取与资源授权内核

Flyway `V11` 创建 `platform_role_assignment`，只允许 `COMPANY_ADMIN + COMPANY` 与 `APP_MANAGER + PLATFORM`，两种作用域均以当前 Company ID 作为 `scope_id`。ACTIVE 赋权唯一，撤销保留原行，重新授予创建新行。授予 actor envelope 支持受控的 `USER` 或 `SYSTEM` 来源，为 M1-09 首管引导与 break-glass 预留事实表达，但 M1-08 不提供任何写端口；数据库也不种管理员。

`PlatformRoleQuery` 只读取 ACTIVE 角色事实，不把 User 就业/账号状态混入查询。认证链先通过 Session/User 状态与授权版本校验，再读取角色并构造 `CurrentActor`；因此 LEFT/DISABLED User 的历史角色事实仍保留，但无法形成有效请求主体。`AuthorizationDecision`/`AuthorizationGuard` 统一实现 403 与隐藏 404，catalog 的纯策略和 `ProjectAccessSnapshotQuery` 则冻结 M2 的可见性边界，不注册 Project 查询实现。

从仓库根目录运行完整门禁：

```powershell
pnpm verify:m1-08
```

仅运行不依赖 Docker 的后端测试：

```powershell
cd backend
.\mvnw.cmd -DskipITs test
```

## M1-07 账号状态与会话失效

`AccountStatusUseCase` 是当前唯一的手工账号启停应用入口。调用方必须提供 Company、目标用户、操作者、目标状态、预期 `rowVersion`、幂等键、请求哈希和原因引用。命令、User 状态与版本、批量会话撤销、Outbox 事件及幂等结果在一个 PostgreSQL 事务中完成；任何事件或持久化失败都会整体回滚。

`SessionRevocationService` 由账号状态命令和完整目录对账共享，只撤销 `status=ACTIVE` 且空闲、绝对有效期均未到期的会话。运行期不新增授权缓存，每个请求继续从数据库复核就业状态、账号状态和 `authorization_version`。该内部用例尚未暴露生产 Controller；管理端 HTTP 适配必须等待角色授权与 Security Audit 能力完成。

```powershell
cd backend
.\mvnw.cmd clean verify
```

## M1-05 通讯录部分失败、对账、离职与返聘

Flyway `V10` 扩展 M1-04 的同步结果约束：成员 outcome 支持 `RETURNED/LEFT`，离职明细使用 `MARK_LEFT`，`returned_count` 计入 discovered outcome 而 `left_count` 独立统计。完整扫描后的单成员资料、映射或写入失败会隔离为 item `FAILED` 并继续，其余成功项持久化，run 以 `PARTIALLY_SUCCEEDED` 完成且跳过缺失成员对账；批次级故障仍以 `FAILED` 结束。旧 run/item 不可恢复，同 trigger key 重放原快照，新 trigger key 才创建新的全量 run。

全量成功收尾会在单一事务中锁定缺失的 ACTIVE WECOM 身份并按 external ID 排序，将 User 和 ExternalIdentity 同时置为 `LEFT`，递增 User 的授权版本，写入离职 item、就业事件、计数和 completed v2 Outbox。相同 external ID 返聘复用原 UUID、恢复双侧 ACTIVE 并优先记录 `RETURNED`；既有 `DISABLED`、离职时间和原因不被覆盖。存在 ACTIVE 基线时空快照会以 `DIRECTORY_EMPTY_SNAPSHOT_REJECTED` 失败关闭，不执行全员离职。

从仓库根运行 `pnpm verify:m1-05`。该入口复核 M1-04 live evidence，并执行后端 `clean verify` 与完整 Node 门禁。本切片不新增 REST/OpenAPI、页面、调度、企微离职回调或失败项定向重试，也不批量撤销 `login_session`；会话撤销留给 M1-07。

## M1-04 通讯录同步批次与全量导入

Flyway `V9` 创建 `directory_sync_run`、`directory_sync_item` 与 `directory_sync_staging_member`。`(company_id, trigger_key_hash)` 固定命令幂等，partial unique index 固定每个 Company 最多一个 RUNNING；活动 run 通过 lease token fencing，每页、每次成员资料暂存和每项落库后续租，过期租约由下一次触发原子关闭并清理暂存。长期 item 不保存姓名、联系方式或部门，RUNNING 期暂存会在 SUCCEEDED/FAILED 的同一事务删除。

模块内部入口为 `DirectorySyncUseCase.execute(DirectorySyncCommand)`，同步执行到终态；同 trigger key 返回既有 run，不同 key 遇活动 run 返回该 run 快照。ID 扫描复用 `/cgi-bin/user/list_id` 的安全适配，省略终止游标必须双扫描一致；独立资料凭据读取 `/cgi-bin/user/get` 与 `/cgi-bin/department/list`。资料全部暂存完成后才按 external user ID 稳定排序逐项短事务调用 M1-02 provisioning。手机号/邮箱使用 PRESENT、CLEAR、UNAVAILABLE 三态，canonical profile hash 使用固定字段顺序、UTF-8 和长度前缀。

运行配置位于 `yumpoo.wecom.directory.*`：`enabled`、`corp-id`、`directory-secret`、`profile-secret`、`page-size`、`lease-duration`、`connect-timeout`、`read-timeout`。默认 page size 1000、租约 5 分钟、连接超时 5 秒、读取超时 20 秒；两个 Secret 必须不同，并只应放在外部 Secret 配置层。除 access token 失效后刷新一次外不自动重试。

从仓库根运行 `pnpm verify:m1-04`。可选真实验证需设置 `SPRING_PROFILES_ACTIVE=m1-04-live`、`YUMPOO_M104_WECOM_ENABLED=true`、`YUMPOO_M104_WECOM_CORP_ID`、两项 `YUMPOO_M104_WECOM_*_SECRET` 和独立的 `YUMPOO_M104_EVIDENCE_HMAC_KEY`，再运行 `pnpm verify:m1-04:live`。该 live harness 不连接业务数据库，只验证生产适配器可读取真实 ID、成员资料和部门字典，并以短期 HMAC 收据把脱敏证据从 `ENV_PENDING` 更新为 `PASS`。

## M1-03 会话与安全底座

Flyway `V8` 为 `identity_user` 增加非负 `authorization_version`，并创建 `login_session`。会话令牌与 CSRF 令牌均为 32 字节随机凭据，持久化层只保存用途隔离的 HMAC-SHA-256 指纹和 keyVersion。活动会话按 8 小时空闲、7 天绝对期限续期，撤销或过期事实保留到绝对到期后 24 小时，再由每小时任务分批清理。

Spring Security 链只匹配 `/api/v1/**`，禁用 HttpSession、表单登录、HTTP Basic 和持久化 SecurityContext。Session Cookie 为 `__Host-yumpoo-session`；CSRF Cookie 为可读的 `__Host-yumpoo-csrf`，写请求通过 `X-XSRF-TOKEN` 回传并与当前 Session 的数据库指纹核对。生产环境必须配置 current HMAC 版本与至少 32 字节 Base64 密钥；previous 版本、密钥和截止时刻只能成组配置。

从仓库根运行 `pnpm verify:m1-03`。本切片仅提供会话/鉴权内核与 HTTP 安全基础，不提供正式 callback、logout、`/me` 或登录页面。

## M1-02 User 与 ExternalIdentity 底座

Flyway `V7` 创建 `identity_user` 与 `external_identity`。User 固定归属 M1-01 的唯一 Company，分别保存 `employment_status=ACTIVE/LEFT` 和 `account_status=ENABLED/DISABLED`；WECOM 外部身份由 `(company_id, provider, external_user_id)` 唯一定位，并通过 `(user_id, provider)` 唯一约束落实一期一对一绑定。个人资料字段不建立唯一索引，也不用于自动合并。

`DirectoryMemberProvisioningService.provisionOrRefresh` 是供后续正式通讯录同步复用的模块内部事务入口。首次观察创建 `ACTIVE + ENABLED` User；后续同外部 ID 只刷新当前资料、hash 和观察时间，保持 User ID、就业状态、账号状态及既有历史事实。实现以派生 advisory lock 处理并发首次建立，含敏感数据的值对象和聚合输出均使用脱敏 `toString()`。

从仓库根运行 `pnpm verify:m1-02`，会执行后端 `clean verify` 和完整 Node 工作区门禁。本切片不提供 REST/OpenAPI、同步批次、就业/账号状态命令、会话、角色或事件发布。

## M1-01 Company 与工作日历底座

`organization` 模块拥有 Company 与 `company_calendar_day`。Flyway `V6` 创建并固定种下唯一 Company：ID 为 `00000000-0000-4000-8000-000000000001`，展示名 `Yumpoo`，时区 `Asia/Shanghai`，周起始日 `MONDAY`，默认工作日 480 分钟。数据库是运行期唯一配置真源；应用不会用环境变量覆盖种子，读取到缺失 Company、非法 IANA 时区或非周一起始配置时会失败关闭。

`organization.api.CompanyConfigurationQuery` 和 `CompanyCalendarQuery` 是跨模块只读入口，不暴露 JDBC 仓储。日历缺省为周一至周五工作、周末休息，日期级覆盖优先；工作日空分钟继承 Company 默认值，非工作日固定为 0。日期与时刻运算使用 `Instant`、`LocalDate`、`ZoneId`，DST 缺口向后移动到首个有效时刻，重叠固定选择较早 offset。

从仓库根运行 `pnpm verify:m1-01`，会执行后端 `clean verify` 和完整 Node 工作区门禁。本切片没有 REST/OpenAPI、前端或日历写命令。

## M0-17 备份与隔离恢复测试原型

`M017BackupRestoreIT` 使用两个独立的 PostgreSQL 17.10 Testcontainers 实例验证 custom-format `pg_dump`/`pg_restore`，并用 test-only 合成表关联 M0-14 内容寻址附件。备份集在 `.partial` 目录完成数据库、附件、普通配置、Secret 恢复描述和逐文件 SHA-256 manifest 后，才以同卷原子移动完成；恢复前必须完整验签并确认数据库和附件目标为空。

测试层 `M017BackupSet` 拒绝危险/非规范路径、符号链接、Windows 大小写碰撞、缺件、额外文件、大小或摘要不符以及包含 Secret 值的恢复描述。恢复后测试核对 Flyway 版本、合成引用和附件真实字节，并只报告孤儿。`M017RetentionPlanner` 只生成 14 daily、8 weekly、6 monthly 的 dry-run 多标签计划，legal hold 阻止删除，但不会把失败集冒充成功代际。

从仓库根运行 `pnpm verify:m0-17`。该命令先校验证据契约，再串联 `verify:m0-16`，最终只接受本次运行在 `out/m0-17` 产生且绑定当前 Git HEAD 的报告。Docker 不可用会直接失败，不使用 H2。以上类型均位于测试源码，不进入生产 JAR；正式任务调度、外部介质、告警、签名/加密和 RPO/RTO 演练留给 M5/M6。

## M0-16 生产 profile 与部署健康检查

`prod` profile 新增严格启动预检。生产进程必须使用 Java 21、精确绑定 `127.0.0.1` 和 1024～65535 端口；公开地址必须是无凭据、query、fragment 或业务路径的 HTTPS origin。应用与 Flyway 必须使用同一个本机 PostgreSQL 数据库，但账号和至少 16 个 Unicode 字符的密码必须彼此独立。

外部配置接口为：

- `yumpoo.deployment.public-base-url`
- `yumpoo.deployment.release-root`
- `yumpoo.deployment.config-root`
- `yumpoo.deployment.secrets-root`
- `yumpoo.deployment.attachment-root`
- `yumpoo.deployment.upload-temp-root`
- `yumpoo.deployment.log-root`

所有目录必须是已存在的绝对真实路径，互不相同或嵌套；持久目录不得位于 release 下，附件与临时上传必须同卷。config、secrets、release 要求可读，附件、临时上传、日志要求可写。启动失败只记录稳定错误码和配置项名称，不回显路径、用户名或 Secret。

Windows 服务从 `C:\ProgramData\Yumpoo\config` 和 `C:\ProgramData\Yumpoo\secrets` 依次加载普通配置和 Secret 覆盖层：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:C:/ProgramData/Yumpoo/config/,file:C:/ProgramData/Yumpoo/secrets/'
java -jar .\target\yumpoo-server.jar
```

readiness 组包含 `db` 与无详情的 `deploymentDirectories` 写探针；附件、临时上传、日志或数据库故障会使 readiness 返回 503/DOWN，liveness 继续返回 200/UP。响应仍严格只暴露 `status`。完整模板、WinSW 2.12.0 锁定信息和安装/升级/回滚顺序位于仓库根 `deployment/windows`。

## M0-15 Electron 登录交接诊断 PoC

M0-15 在 M0-12 企微身份网关之上增加可复用的 desktop state/PKCE/handoff 技术闭环，但不创建正式用户或桌面会话。M0 开发门禁使用自动化测试验证交接协议与安全边界，不要求真实企微 OAuth 或扫码登录；M4-14 使用受控身份提供者验证本地桌面语义，真实企微、公司 HTTPS、packaged app 和协议唤起统一在 M6-01 部署/发布环境门禁补验。诊断 Controller 采用双重门禁：profile 必须包含 `m0-15-live`，且 `YUMPOO_M015_WECOM_ENABLED=true`；默认启动时三条路径均不注册：

- `GET /_m0/m0-15/electron/auth/authorize`
- `GET /_m0/m0-15/wecom/callback`
- `POST /_m0/m0-15/electron/auth/exchange`

启动真实诊断后端前，从外部注入 `YUMPOO_M015_WECOM_CORP_ID`、`YUMPOO_M015_WECOM_AGENT_ID`、`YUMPOO_M015_WECOM_APP_SECRET`、`YUMPOO_M015_WECOM_CALLBACK_URI`、`YUMPOO_M015_WECOM_ALLOWED_MEMBER_IDS` 和独立的 `YUMPOO_M015_EVIDENCE_HMAC_KEY`。callback 必须是同源 HTTPS 的 `/_m0/m0-15/wecom/callback` 且不带 query/fragment；证据密钥至少 32 个 UTF-8 字节和 8 种字符，不能包含常见占位值或复用企微 Secret。

authorize 只接受随机 `state`、PKCE `codeChallenge` 与 `S256`，通过 Secure/HttpOnly/SameSite=Lax Cookie 绑定 OAuth nonce 和 desktop state。callback 成功后只向 `yumpoo://auth/callback` 发送短时 opaque code 与原 state；exchange 的 JSON 仅为 `code`、`state`、`codeVerifier`。明文 state、verifier、handoff code、企微身份和 Secret 不进入持久化或日志，handoff 采用哈希和条件更新原子消费，重复、过期、state/PKCE 不匹配均失败关闭。

exchange 成功只返回一次短期 HMAC 收据；其中 requestId、企业/成员指纹和签名仅用于 live runner 即时验签，随后删除，不写入最终 M0-15 证据。正式 `/api/v1/electron/auth/attempts`、`/exchange`、logout、User/ExternalIdentity/LoginSession、账号状态编排和 Windows 凭据仍属于 M1/M4。

从仓库根目录运行 `pnpm verify:m0-15`，完成证据校验、Windows x64 壳打包/内容扫描并串联 `verify:m0-14`。真实企微、HTTPS、packaged app 与协议唤起均就绪后，按根 README 注入短期收据和 manifest 路径，再运行 `pnpm verify:m0-15:live`。当前 live runner 只提供安全 preflight 和收据验证入口，不模拟企微或代签桌面结果；缺少自动串联、任何门禁或安全输入都会失败，`evidence/m0-15/live-verification.json` 保持 `NOT_RUN`。

## M0-14 file-security PoC

`filestorage` 现包含固定 64 KiB 缓冲的隔离接收、100 MiB 精确上限、SHA-256 内容寻址、Tika 类型识别、ZIP/OOXML 判别、扫描端口、Defender 适配器和同卷原子发布。它们是后续正式附件功能可复用的技术核心；当前没有生产 Attachment 表、Controller 或正式 OpenAPI operation。

从仓库根目录运行 `pnpm verify:m0-14`。该命令额外在 `-Xmx96m` 下点名运行 `M014BoundedHeapVerification`，并通过 test-only HTTP/PostgreSQL 探针覆盖断流、并发完成、扫描事务边界、授权撤销、413/415、隐藏 404 和最终事务回滚。真实 Defender/NTFS 验证及所需变量见仓库根 README；受控 Windows 环境具备后运行 `pnpm verify:m0-14:live` 并取得签名 `PASS` 证据，未执行前保持 `NOT_RUN`，不阻塞 M0 本地开发门禁。

YumpooPlatform 一期 M0-13 的 Spring 后端、数据库、内部事件契约与企微 OAuth/通讯录验证骨架。当前产物是单 Maven 模块、单可执行 JAR 的模块化单体，包含 PostgreSQL、Flyway、真实库测试、统一错误、请求关联、乐观锁、持久化幂等、事务 Outbox、消费去重和默认关闭的企微诊断流程，但不包含正式登录或通讯录同步业务功能。

## 环境

- JDK 21，且 `JAVA_HOME` 指向 JDK 21。
- 构建统一使用仓库内 Maven Wrapper 3.9.9，不依赖全局 Maven。
- Docker 可用；`verify` 会启动固定的 `postgres:17.10-alpine`，不可用时直接失败。

## 构建与运行

在 `backend` 目录执行：

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

`clean verify` 会在 Failsafe 阶段运行 PostgreSQL 17.10 集成测试，验证空库迁移、重复校验、checksum 变化拒绝、UTF-8、UTC、健康探针、幂等事务、Outbox、OAuth attempt 的过期/原子消费/重放拒绝，以及 M0-13 通讯录分页、失败保护和幂等对账探针。

## 数据库配置与运行

应用运行账号通过标准 Spring Boot 环境变量配置：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/yumpoo'
$env:SPRING_DATASOURCE_USERNAME = 'yumpoo_app'
$env:SPRING_DATASOURCE_PASSWORD = '<application-password>'
```

共享和生产环境必须另外提供具有 DDL 权限的迁移账号：

```powershell
$env:SPRING_FLYWAY_URL = 'jdbc:postgresql://127.0.0.1:5432/yumpoo'
$env:SPRING_FLYWAY_USER = 'yumpoo_migrator'
$env:SPRING_FLYWAY_PASSWORD = '<migration-password>'
.\mvnw.cmd spring-boot:run
```

本地一次性验证可省略 `SPRING_FLYWAY_*`，此时 Flyway 复用应用数据源；共享和生产环境不得这样配置。密码、连接 Secret 和生产凭据不得进入 Git。

数据库固定使用单一 `yumpoo` schema。迁移位于 `src/main/resources/db/migration/<owner-module>/`，命名为 `V<version>__<description>.sql`，进入共享环境后不得修改。启动时保持 `validateOnMigrate=true`、`cleanDisabled=true`、`baselineOnMigrate=false`，禁止自动 `repair`。

M0-10 新增唯一的应用侧技术表 `idempotency_record`，不创建业务表。`IdempotentCommandExecutor.execute(command, callback)` 在单一 `REQUIRED` 事务中依次认领幂等键、执行业务回调并保存完成结果，记录状态仅为 `PROCESSING` 或 `COMPLETED`。相同键和请求哈希在完成后重放结果，不同哈希复用同一键返回 `IDEMPOTENCY_KEY_REUSED`，读取到已存在的处理中记录时返回 `REQUEST_IN_PROGRESS`。回调失败会回滚同一事务内的认领记录和业务事实，后续重试重新认领并执行。`lease_until` 和 `expires_at` 按一期基线预留崩溃恢复与清理元数据；M0-10 只写入元数据，不据此接管、自动删除或持久化失败状态。

M0-11 新增 `outbox_event` 与 `outbox_consumer_receipt`。`TransactionalEventPort.append(EventDraft)` 使用 `MANDATORY` 事务传播，补齐 UUIDv4、UTC 时间和当前请求关联后，与业务事实及幂等结果同事务写入。dispatcher 默认启用，参数可通过 `YUMPOO_OUTBOX_ENABLED`、`YUMPOO_OUTBOX_POLL_DELAY`、`YUMPOO_OUTBOX_INITIAL_DELAY`、`YUMPOO_OUTBOX_BATCH_SIZE`、`YUMPOO_OUTBOX_CONCURRENCY`、`YUMPOO_OUTBOX_LEASE_DURATION` 覆盖；默认值依次为 `true`、`1s`、`1s`、`50`、`2`、`5m`。

每次领取使用短事务、`FOR UPDATE SKIP LOCKED` 和 owner + lease token；到期重试及租约过期项均可领取。同聚合的低版本未完成或 `DEAD` 时高版本保持阻塞。消费者的数据库效果与 receipt 在独立新事务中提交，重复或并发投递只保留一份效果；多消费者中已成功者在后续重试时跳过。可恢复失败采用 1 分钟、5 分钟、30 分钟、2 小时、8 小时加正向抖动，第六次进入 `DEAD`；永久错误、无消费者和不支持版本首次即 `DEAD`。

## M0-12 企微 OAuth 诊断流程

M0 开发门禁为 `pnpm verify:m0-12`，通过受控测试边界验证授权参数、corp/member 映射、state/nonce、原子消费、并发/重放拒绝、供应商失败映射和脱敏；不要求真实成员授权或扫码、公开域名和真实 HTTPS 回调。下面的 live 流程保留为后续受控联调环境门禁，未执行时 `NOT_RUN` 是预期状态但不代表通过。

真实企微路由采用双重门禁，默认不会注册。启动前必须同时设置 `SPRING_PROFILES_ACTIVE=m0-12-live`、`YUMPOO_M012_WECOM_ENABLED=true`，并从外部注入：

- `YUMPOO_M012_WECOM_CORP_ID`
- `YUMPOO_M012_WECOM_AGENT_ID`
- `YUMPOO_M012_WECOM_APP_SECRET`
- `YUMPOO_M012_WECOM_CALLBACK_URI`
- `YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS`
- `YUMPOO_M012_EVIDENCE_HMAC_KEY`

callback URI 必须是固定、不带 query 的 HTTPS 地址；证据 HMAC 密钥至少包含 32 个 UTF-8 字节和 8 种字符，且不得使用占位值或复用企微应用 Secret。缺项、弱密钥或不安全 callback 会拒绝启用。启用后只增加 `/_m0/m0-12/wecom/authorize` 与 `/_m0/m0-12/wecom/callback` 两个诊断路径；它们不属于 `/api/v1` 正式契约，不创建用户、外部身份或登录会话。

在 HTTPS 反向代理和后端均运行后，从仓库根目录执行 `pnpm verify:m0-12:live`。脚本先执行配置预检和四类统一 401 负向检查，再在不回显输入的情况下验证同一成员的两份 HMAC 签名成功收据。完整步骤、可选 `YUMPOO_M012_LIVE_BASE_URL` 和证据约束见仓库根 README。真实企业验证没有执行时，`evidence/m0-12/live-verification.json` 必须保持 `NOT_RUN`，不得手工改成 `PASS`。

## M0-13 企微通讯录诊断流程

M0-13 live runner 不是常驻 Controller，也不增加 HTTP 路径。运行前必须同时提供以下五个环境变量；profile 与 enabled 是双重门禁：

- `SPRING_PROFILES_ACTIVE`（包含 `m0-13-live`）
- `YUMPOO_M013_WECOM_ENABLED=true`
- `YUMPOO_M013_WECOM_CORP_ID`
- `YUMPOO_M013_WECOM_DIRECTORY_SECRET`
- `YUMPOO_M013_EVIDENCE_HMAC_KEY`

通讯录 Secret 只用于从 Java 直连企微时的可信出口 IP 访问；`HTTP_PROXY/HTTPS_PROXY` 环境变量不会自动改变本 live harness 的 Java 出口。证据 HMAC 密钥至少包含 32 个 UTF-8 字节和 8 种 Unicode code point，不得包含常见占位标记，也不得与该 Secret 相同。五项都只能从受控外部环境注入，测试、日志和 Maven 输出不得回显其值。

从仓库根目录执行 `pnpm verify:m0-13:live`。Node runner 清理旧收据后，只运行 Maven 类 `M013WeComDirectoryLiveVerification`。Java 在任何 probe 对账前完成两次 `limit=1` 窄页扫描和一次 `limit=10000` 宽页扫描；两次窄页必须观察真实非空游标，三次必须以供应商省略终止游标结束，并得到完全一致的企业、快照和成员 HMAC。只有 test-only `M013OmittedCursorConfirmation` 能在这些条件同时成立时确认候选快照，主 collector 仍保持严格失败。全部安全场景通过后 Java 写入 `target/m0-13-live-receipt.json`；收据签名必须把 UTF-8 `receipt\0` 置于 canonical 正文之前，与 corp/member/snapshot 指纹执行域分离。Node 验签后才原子更新 `evidence/m0-13/live-verification.json`，并在 `finally` 删除收据。验证失败不会改写已有证据。当前 M0-12 证据状态只作为 `m012DependencyStatus` 记录，不作为 M0-13 live runner 的通过前提。

收据和最终证据都禁止出现人数、页数、游标、原始企微成员 ID、个人资料、Secret、token 或完整企微响应。`providerPaginationObserved` 与 `providerTerminalCursorOmissionConfirmed` 分别记录真实分页和终止字段省略已完成交叉确认；人工诊断输出的通讯录或 userid 不进入仓库。正式 Company、User、ExternalIdentity、DirectorySyncRun、管理 API、调度和会话撤销仍属于 M1，不在本诊断流程中创建。

通讯录读取的冻结外部契约为 `POST /cgi-bin/user/list_id`：`limit` 范围 1～10000，官方以空 `next_cursor` 表示结束；真实企业调用则观察到分页中的游标非空、终止页省略该字段。网关保留省略游标页的成员，主 `DirectorySnapshotCollector` 仍返回 `Incomplete(MISSING_CURSOR)`，仅 live 的三快照交叉确认可继续测试探针对账，不能直接成为 M1 生产同步语义。该接口只接受通讯录同步 Secret，且只返回成员 ID、不读取成员资料。`/cgi-bin/gettoken` 正常 token 生命周期为 7200 秒，适配器必须缓存并提前刷新。`-1`、`45009` 归为可退避重试；`40001`、`48002`、`60020` 归为凭据、权限或可信 IP 配置失败；`40014`、`42001` 只触发一次 token 缓存失效、刷新和重试。上述限制由 `externalLimitsRecorded` 检查锁定，最终证据不保存任何运行时数量或供应商正文。

默认仅监听 `127.0.0.1:8080`。需要修改端口时设置 `YUMPOO_SERVER_PORT`：

```powershell
$env:YUMPOO_SERVER_PORT = '18080'
.\mvnw.cmd spring-boot:run
```

打包完成后也可运行：

```powershell
java -jar .\target\yumpoo-server.jar
```

当前仅暴露以下运行探针，且不返回组件详情：

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

M0-12 与 M0-13 不增加生产探针、正式业务 Controller 或 OpenAPI path；双门禁诊断能力只用于真实企微验证。`/api/v1` 的统一错误、分页、条件头和客户端头以仓库根目录 `contracts/openapi/yumpoo-v1.yaml` 为唯一 HTTP 契约；内部事件信封、目录、payload Schema 和样例位于 `contracts/events`。

## 模块

| 设计名称 | Java 包 | 职责 |
| --- | --- | --- |
| `foundation` | `foundation` | ID、时钟、错误模型和基础契约 |
| `organization` | `organization` | Company、时区、工作日历和系统配置 |
| `identity_access` | `identityaccess` | 身份、用户状态、会话和平台角色 |
| `catalog` | `catalog` | Workspace、Product、Project 和成员归属 |
| `template_workflow` | `templateworkflow` | 固定模板、字段目录和状态迁移定义 |
| `file_storage` | `filestorage` | 附件元数据、隔离、扫描和对象落盘 |
| `work_item` | `workitem` | Content、Work Item、Updates、关系和 Activity 来源事实 |
| `product_feedback` | `productfeedback` | Product Feedback 独立聚合及双侧投影 |
| `worklog` | `worklog` | 工时、周提交、审批、撤回和更正 |
| `notification` | `notification` | 站内通知、投递和桌面推送 |
| `audit` | `audit` | Security Audit 和 Activity 投影 |
| `reporting` | `reporting` | 权限过滤后的只读统计 |
| `administration` | `administration` | 管理编排和运行状态摘要 |

每个模块固定包含：

```text
<module>
├─ api
├─ application
├─ domain
└─ infrastructure
```

ArchUnit 在 `verify` 阶段检查模块允许依赖图、循环依赖、层级方向、domain 框架独立性和 API 直接访问 JDBC 等违规行为。跨模块调用只能进入目标模块 `api`；`foundation` 是共享内核，但其 `infrastructure` 仍不可跨模块访问。

## 当前边界

本切片在 foundation 的事件/Outbox/消费骨架上增加 OAuth attempt 技术表、企微身份与通讯录适配器，以及默认关闭的诊断 Controller/live runner，但不创建正式业务表、User、ExternalIdentity、DirectorySyncRun、LoginSession、Security Audit 或 Activity 投影。控制台结构化日志只记录受控关联字段；payload、异常原文、请求体、原始身份、成员清单、授权 code、token 与 Secret 不进入持久化失败信息、证据或日志。正式身份绑定和会话、同步批次、业务事件、通知投递、人工重排、指标告警、数据清理、日志轮转和管理页面均由后续切片实现。
## M1-11 身份管理查询与同步触发边界

- `/api/v1/company` 对有效登录用户开放只读公司配置；`/api/v1/admin/integrations/wecom/status`、成员目录和同步运行查询仅允许可用的 `APP_MANAGER` 或 `COMPANY_ADMIN`。
- 只有 `COMPANY_ADMIN` 可调用手工同步和既有账号启停命令。成员查询按 Company 在 SQL 中隔离，并支持姓名包含、企微外部 ID 精确匹配、就业与账号状态筛选以及稳定的 0 基分页。
- 同步认领明确区分 `NEW`、`REPLAY` 和 `ACTIVE_CONFLICT`。失败项只返回稳定错误码和脱敏成员引用；供应商原文、完整 Corp ID、Secret、token 与回调地址不得进入响应或日志。
- 企微状态从外部配置属性和同步运行事实派生，不新建 Secret 表。生产 Secret 仍只能通过受控外部配置注入，管理 API 与页面没有写入口。

从仓库根运行 `pnpm verify:m1-11`，串联事件契约、后端 `clean verify` 与完整 Node 门禁。

## M1-10 Security Audit 与治理 API 边界

- Flyway `V14` 创建不可更新、不可删除的 `security_audit_event`；唯一事实键防止幂等重放产生同义记录，内部查询固定按 Company + requestId 隔离并以 `occurredAt DESC, id DESC` 分页，size 上限 100。
- `identityaccess` 只依赖 `audit.api` 追加/查询端口，审计模块只依赖 foundation。持久化内容仅含动作、结果、主体/角色快照、目标、理由引用、最小状态摘要及关联标识，不保存请求体、异常原文、Cookie、CSRF、企微凭据、联系方式或原始 IP。
- 正式 `/api/v1/admin` 治理端点提供成员治理快照、角色读写与账号启停；角色写仅 APP_MANAGER，账号启停仅 COMPANY_ADMIN，读取允许两者。写模型不接受 actor、role、scope 或任何版本字段。
- 审计 HTTP 查询、`SECURITY_AUDIT_READ`、查询自身审计、CapabilityAssignment、哈希链/WORM 及管理页面不在 M1-10 范围。

从仓库根运行 `pnpm verify:m1-10`，串联事件契约、后端 `clean verify`、OpenAPI/生成客户端和完整 Node 门禁。PostgreSQL 集成测试要求 Docker Desktop Linux engine 可用。

## M1-09 角色治理边界

- `PlatformRoleManagementUseCase` 提供 `APP_MANAGER`、`COMPANY_ADMIN` 授予/撤销，只有可用 `APP_MANAGER` 可以调用；`PlatformRoleAssignmentQueryUseCase` 允许 `APP_MANAGER` 与 `COMPANY_ADMIN` 查询同公司历史，普通结果不包含自由文本理由。
- 用户角色命令固定使用 15 分钟最近认证窗口，并携带会话授权版本、目标 User 或 Assignment 并发版本、理由引用和幂等键。角色固定推导 `PLATFORM/COMPANY` scope，不接受调用方自定义 scope。
- `app_manager_governance_state` 必须先于 User、Assignment、Session 加锁。最后可用 `APP_MANAGER` 的主动撤销和账号禁用返回 `INVALID_STATE_TRANSITION`；目录离职不受保护阻塞，但会产生缺失/恢复事件。
- administration 只消费缺失/恢复事实并维护 `governance_issue`，不复制角色事实，也不参与授权判断。
- 本切片没有角色管理 Controller 或 OpenAPI path。统一 Security Audit、失败审计及审计失败关闭由 M1-10 实现后，才能接通正式写 HTTP。

维护 Runner 仅在 `yumpoo.maintenance.app-manager.enabled=true` 时注册。必须以 `spring.main.web-application-type=none` 运行，同时关闭 Outbox 调度，并提供 `BOOTSTRAP` 或 `BREAK_GLASS`、内部 `userId` 和 1～160 字符理由引用；成功或失败后进程结束。
