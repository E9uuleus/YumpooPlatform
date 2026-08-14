package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Objects;
import java.util.UUID;

public record MaintenanceRoleCommand(
        UUID companyId,
        UUID targetUserId,
        MaintenanceRoleMode mode,
        String reasonReference
) {
    public MaintenanceRoleCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        reasonReference = GrantPlatformRoleCommand.validateReason(reasonReference);
    }
}
