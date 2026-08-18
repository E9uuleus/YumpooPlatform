package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "yumpoo.maintenance.initial-identity")
public record InitialIdentityBootstrapRunnerProperties(
        boolean enabled,
        Path inputFile,
        String reasonReference
) {
}
