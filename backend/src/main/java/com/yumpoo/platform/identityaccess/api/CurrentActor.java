package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CurrentActor(
        UUID userId,
        UUID companyId,
        long authorizationVersion,
        Set<PlatformRoleCode> platformRoles
) {

    public CurrentActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (authorizationVersion < 0) {
            throw new IllegalArgumentException("authorizationVersion must not be negative");
        }
        platformRoles = Set.copyOf(Objects.requireNonNull(
                platformRoles,
                "platformRoles must not be null"
        ));
        if (platformRoles.contains(PlatformRoleCode.APP_MANAGER)) {
            var effective = new java.util.HashSet<>(platformRoles);
            effective.add(PlatformRoleCode.COMPANY_ADMIN);
            platformRoles = Set.copyOf(effective);
        }
    }

    public PlatformRoleTier memberTier() {
        return hasRole(PlatformRoleCode.APP_MANAGER) ? PlatformRoleTier.APP_MANAGER
                : hasRole(PlatformRoleCode.COMPANY_ADMIN) ? PlatformRoleTier.COMPANY_ADMIN
                : PlatformRoleTier.COMPANY_MEMBER;
    }

    public boolean hasRole(PlatformRoleCode role) {
        return platformRoles.contains(Objects.requireNonNull(role, "role must not be null"));
    }
}
