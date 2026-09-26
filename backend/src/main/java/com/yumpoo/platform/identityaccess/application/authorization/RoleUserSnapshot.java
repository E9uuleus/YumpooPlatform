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
    public Set<ManagedPlatformRole> effectiveRoles() {
        if (!activeRoles.contains(ManagedPlatformRole.APP_MANAGER)) return Set.copyOf(activeRoles);
        return Set.of(ManagedPlatformRole.APP_MANAGER, ManagedPlatformRole.COMPANY_ADMIN);
    }

    public boolean hasEffectiveRole(ManagedPlatformRole role) {
        return activeRoles.contains(role) || role == ManagedPlatformRole.COMPANY_ADMIN
                && activeRoles.contains(ManagedPlatformRole.APP_MANAGER);
    }

    public boolean available() {
        return "ACTIVE".equals(employmentStatus) && "ENABLED".equals(accountStatus);
    }
}
