package com.yumpoo.platform.identityaccess.infrastructure.bootstrap;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yumpoo.maintenance.initial-identity")
public record InitialIdentityBootstrapRunnerProperties(boolean enabled, Path inputFile, String reasonReference) {}
