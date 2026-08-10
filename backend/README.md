# Yumpoo Server

YumpooPlatform 一期 M0-12 的 Spring 后端、数据库、内部事件契约与企微 OAuth 验证骨架。当前产物是单 Maven 模块、单可执行 JAR 的模块化单体，包含 PostgreSQL、Flyway、真实库测试、统一错误、请求关联、乐观锁、持久化幂等、事务 Outbox、消费去重和默认关闭的企微诊断流程，但不包含正式登录业务功能。

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

`clean verify` 会在 Failsafe 阶段运行 PostgreSQL 17.10 集成测试，验证空库迁移、重复校验、checksum 变化拒绝、UTF-8、UTC、健康探针、幂等事务、Outbox 以及 OAuth attempt 的过期、原子消费和重放拒绝行为。

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

真实企微路由采用双重门禁，默认不会注册。启动前必须同时设置 `SPRING_PROFILES_ACTIVE=m0-12-live`、`YUMPOO_M012_WECOM_ENABLED=true`，并从外部注入：

- `YUMPOO_M012_WECOM_CORP_ID`
- `YUMPOO_M012_WECOM_AGENT_ID`
- `YUMPOO_M012_WECOM_APP_SECRET`
- `YUMPOO_M012_WECOM_CALLBACK_URI`
- `YUMPOO_M012_WECOM_ALLOWED_MEMBER_IDS`
- `YUMPOO_M012_EVIDENCE_HMAC_KEY`

callback URI 必须是固定、不带 query 的 HTTPS 地址；证据 HMAC 密钥至少包含 32 个 UTF-8 字节和 8 种字符，且不得使用占位值或复用企微应用 Secret。缺项、弱密钥或不安全 callback 会拒绝启用。启用后只增加 `/_m0/m0-12/wecom/authorize` 与 `/_m0/m0-12/wecom/callback` 两个诊断路径；它们不属于 `/api/v1` 正式契约，不创建用户、外部身份或登录会话。

在 HTTPS 反向代理和后端均运行后，从仓库根目录执行 `pnpm verify:m0-12:live`。脚本先执行配置预检和四类统一 401 负向检查，再在不回显输入的情况下验证同一成员的两份 HMAC 签名成功收据。完整步骤、可选 `YUMPOO_M012_LIVE_BASE_URL` 和证据约束见仓库根 README。真实企业验证没有执行时，`evidence/m0-12/live-verification.json` 必须保持 `NOT_RUN`，不得手工改成 `PASS`。

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

M0-12 不增加生产探针、正式业务 Controller 或 OpenAPI path；双门禁诊断路由只用于真实企微验证。`/api/v1` 的统一错误、分页、条件头和客户端头以仓库根目录 `contracts/openapi/yumpoo-v1.yaml` 为唯一 HTTP 契约；内部事件信封、目录、payload Schema 和样例位于 `contracts/events`。

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

本切片在 foundation 的事件/Outbox/消费骨架上增加 OAuth attempt 技术表、企微适配器和默认关闭的诊断 Controller，但不创建正式业务表、User、ExternalIdentity、LoginSession、Security Audit 或 Activity 投影。控制台结构化日志只记录受控关联字段；payload、异常原文、请求体、原始身份、授权 code、token 与 Secret 不进入持久化失败信息、证据或日志。正式身份绑定和会话、业务事件、通知投递、人工重排、指标告警、数据清理、日志轮转和管理页面均由后续切片实现。
