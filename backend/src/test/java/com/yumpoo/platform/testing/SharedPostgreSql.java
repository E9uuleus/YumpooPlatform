package com.yumpoo.platform.testing;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** 每个测试 JVM 一个 PostgreSQL；模板库只迁移一次，每个 Spring 上下文克隆独立数据库。 */
public final class SharedPostgreSql {

    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:17.10-alpine");
    private static final String TEMPLATE = "yumpoo_template";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final PostgreSQLContainer CONTAINER = start();

    private SharedPostgreSql() {
    }

    public static String createDatabase() {
        String name = "yumpoo_ctx_" + DATABASES.incrementAndGet();
        execute("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
        return jdbcUrl(name);
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("yumpoo_test")
                .withUsername("yumpoo_test")
                .withPassword("yumpoo_test")
                .withEnv("TZ", "UTC")
                .withCommand("postgres", "-c", "timezone=UTC", "-c", "max_connections=300");
        container.start();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + TEMPLATE);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法创建测试模板库", exception);
        }
        // 与 application.yml 的 spring.flyway 保持一致；上下文启动时 Spring 的 Flyway 仍会校验克隆库。
        Flyway.configure()
                .dataSource("jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432)
                        + "/" + TEMPLATE, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("yumpoo")
                .schemas("yumpoo")
                .createSchemas(true)
                .validateOnMigrate(true)
                .ignoreMigrationPatterns("*:future")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        return container;
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/" + database;
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}
