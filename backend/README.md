# Yumpoo Server

YumpooPlatform 一期 M0-09 的 Spring 后端、数据库与 HTTP 契约骨架。当前产物是单 Maven 模块、单可执行 JAR 的模块化单体，包含 PostgreSQL、Flyway、真实库测试、统一错误与 requestId 基础，但不包含业务功能。

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

`clean verify` 会在 Failsafe 阶段运行 PostgreSQL 17.10 集成测试，验证空库迁移、重复校验、checksum 变化拒绝、UTF-8、UTC 和健康探针。

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

M0-09 不增加生产探针或业务 Controller。`/api/v1` 的统一错误、分页、条件头和客户端头以仓库根目录 `contracts/openapi/yumpoo-v1.yaml` 为唯一契约；九类错误通过 test-only Controller 验证，不进入生产 JAR。

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

本切片不创建业务表，不实现真实认证/授权、客户端版本策略、条件更新、幂等记录、Outbox、结构化日志、企微集成或任何生产业务 Controller。M0-10 实现乐观锁与幂等最小闭环，M0-11 实现 requestId 向事件和 Outbox 的贯穿，身份与 426 的真实策略分别由后续阶段实现。
