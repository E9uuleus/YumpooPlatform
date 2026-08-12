package com.yumpoo.platform.foundation.infrastructure.deployment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@EnableConfigurationProperties(DeploymentProperties.class)
class DeploymentConfiguration {

    @Bean
    DeploymentPaths deploymentPaths(
            DeploymentProperties properties,
            Environment environment
    ) {
        return new DeploymentPreflight(properties, environment).validate();
    }

    @Bean("deploymentDirectories")
    HealthIndicator deploymentDirectories(DeploymentPaths paths) {
        return new DeploymentDirectoryProbe(paths);
    }
}
