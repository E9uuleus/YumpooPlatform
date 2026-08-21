package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.UUID;

public record MaintenanceRoleActor(
        UUID userId,
        long authorizationVersion
) {
}
