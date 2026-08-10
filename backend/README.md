# Yumpoo Server

YumpooPlatform 一期 M0-10 的 Spring 后端、数据库与 HTTP 契约骨架。当前产物是单 Maven 模块、单可执行 JAR 的模块化单体，包含 PostgreSQL、Flyway、真实库测试、统一错误、requestId、乐观锁守卫和持久化幂等命令基础，但不包含正式业务功能。

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

`clean verify` 会在 Failsafe 阶段运行 PostgreSQL 17.10 集成测试，验证空库 V1/V2 迁移、重复校验、checksum 变化拒绝、UTF-8、UTC、健康探针、同版本并发竞争和幂等记录闭环。

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

M0-10 不增加生产探针、正式业务 Controller 或 OpenAPI 路径。`/api/v1` 的统一错误、分页、条件头和客户端头以仓库根目录 `contracts/openapi/yumpoo-v1.yaml` 为唯一契约；契约错误和并发行为通过 test-only Controller 或 PostgreSQL probe 验证，不进入生产 JAR。

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

本切片实现乐观锁与幂等记录最小闭环，但不创建业务表，也不实现具体聚合的 Repository 条件更新、资源可见性复查或任何正式业务 Controller。真实业务端点接入留给后续业务切片；M0-11 实现 requestId 向领域事件和 Outbox 的贯穿。真实认证/授权、客户端版本策略、结构化日志、企微集成，以及身份与 426 的正式策略也均由后续阶段实现。
