# Agent Note: 集成测试共享 PostgreSQL 实例并按上下文克隆模板库

Status: implemented

## Problem

每个 Spring 测试上下文启动独立 PostgreSQL 容器并重复执行全量迁移，增加集成测试耗时和本机内存压力。直接共享同一个数据库又会取消原有的上下文数据隔离。

## Decision

[SharedPostgreSql](../../../../backend/src/test/java/com/yumpoo/platform/testing/SharedPostgreSql.java) 在每个测试 JVM 中启动一个 PostgreSQL 17.10 容器，将连接上限设为 300，并按应用的 Flyway 配置迁移一次模板库。每个 Spring 上下文克隆独立数据库，应用启动时仍由 Flyway 校验。

标准 JDBC 与随机端口 HTTP 集成测试分别使用 `JdbcIntegrationTest`、`HttpIntegrationTest`，统一关闭 Outbox，并延迟项目清除与附件维护任务。特殊配置测试保留原注解；需要注入容器 bean 的 `M016ReadinessFailureIT` 与 `YumpooServerApplicationIT` 使用独占容器配置。显式自管容器的迁移等测试保持原生命周期。

Spring 测试上下文缓存上限为 16；共享库连接池最小空闲连接数为 1，空闲超时为 30 秒。

## Alternatives considered

- 每个上下文独立容器：隔离清晰，但反复启动容器与迁移成本高。
- 所有上下文共享一个数据库：启动更少，但不同配置的测试可能相互污染数据。
- 共享实例、每个库重新迁移：保留隔离，但仍重复执行全量迁移；模板克隆可复用迁移结果。

## Consequences

容器由测试 JVM 生命周期持有，Spring 关闭上下文只释放连接池。测试不能停止共享实例；需要停库的测试必须使用独占配置。新增应用 Flyway 配置时必须同步模板初始化配置。元注解合并的是配置一致的上下文，测试事务与断言保持原样。
