package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "yumpoo.maintenance.initial-identity",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(InitialIdentityBootstrapRunnerProperties.class)
public class InitialIdentityBootstrapRunnerConfiguration {
}
