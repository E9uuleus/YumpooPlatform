package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Set;
import java.util.UUID;

public record RoleUserSnapshot(
        UUID userId,
        UUID companyId,
        String employmentStatus,
        String accountStatus,
        long authorizationVersion,
        long rowVersion,
        Set<ManagedPlatformRole> activeRoles
) {
    public boolean available() {
        return "ACTIVE".equals(employmentStatus) && "ENABLED".equals(accountStatus);
    }
}
