package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yumpoo.maintenance.app-manager")
public record MaintenanceRoleRunnerProperties(
        boolean enabled, String mode, UUID targetUserId, String reasonReference) {}
