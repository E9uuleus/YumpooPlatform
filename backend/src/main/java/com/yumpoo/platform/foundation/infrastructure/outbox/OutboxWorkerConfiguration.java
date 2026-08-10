package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.outbox.OutboxRuntimeSettings;
import com.yumpoo.platform.foundation.application.outbox.OutboxTaskExecutor;
import com.yumpoo.platform.foundation.application.outbox.OutboxWorkerIdentity;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxWorkerConfiguration {

    @Bean
    OutboxRuntimeSettings outboxRuntimeSettings(OutboxProperties properties) {
        return new OutboxRuntimeSettings(
                properties.getBatchSize(),
                properties.getLeaseDuration()
        );
    }

    @Bean
    OutboxWorkerIdentity outboxWorkerIdentity() {
        return new OutboxWorkerIdentity("yumpoo-" + UUID.randomUUID());
    }

    @Bean
    OutboxTaskExecutor outboxTaskExecutor(OutboxProperties properties) {
        return new ThreadPoolOutboxTaskExecutor(
                properties.getConcurrency(),
                properties.getBatchSize()
        );
    }
}
