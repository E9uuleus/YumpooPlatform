package com.yumpoo.platform.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestContainerConfiguration {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:17.10-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("yumpoo_test")
                .withUsername("yumpoo_test")
                .withPassword("yumpoo_test")
                .withEnv("TZ", "UTC")
                .withCommand("postgres", "-c", "timezone=UTC");
    }
}
