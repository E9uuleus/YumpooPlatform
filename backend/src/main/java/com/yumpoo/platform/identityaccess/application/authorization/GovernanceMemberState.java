package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Set;
import java.util.UUID;

public record GovernanceMemberState(
        UUID userId,
        String displayName,
        String employmentStatus,
        String accountStatus,
        Set<ManagedPlatformRole> platformRoles,
        long authorizationVersion,
        long rowVersion
) {
    public GovernanceMemberState {
        platformRoles = Set.copyOf(platformRoles);
    }
}
