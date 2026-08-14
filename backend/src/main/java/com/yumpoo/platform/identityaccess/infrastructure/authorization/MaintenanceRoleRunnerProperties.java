package com.yumpoo.platform.identityaccess.infrastructure.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "yumpoo.maintenance.app-manager")
public record MaintenanceRoleRunnerProperties(
        boolean enabled,
        String mode,
        UUID targetUserId,
        String reasonReference
) {
}
