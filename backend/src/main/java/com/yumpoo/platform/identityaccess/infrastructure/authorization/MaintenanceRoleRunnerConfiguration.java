package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "yumpoo.maintenance.app-manager",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(MaintenanceRoleRunnerProperties.class)
public class MaintenanceRoleRunnerConfiguration {
}
