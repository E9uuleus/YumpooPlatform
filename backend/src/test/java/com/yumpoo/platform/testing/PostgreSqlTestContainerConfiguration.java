package com.yumpoo.platform.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestContainerConfiguration {

    @Bean
    DynamicPropertyRegistrar sharedPostgreSqlDatabase() {
        String url = SharedPostgreSql.createDatabase();
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", SharedPostgreSql::username);
            registry.add("spring.datasource.password", SharedPostgreSql::password);
            // 已结束的测试类的上下文仍在缓存中；空闲连接尽快归还，避免占满共享实例。
            registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
            registry.add("spring.datasource.hikari.idle-timeout", () -> "30s");
        };
    }
}
