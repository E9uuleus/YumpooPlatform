package com.yumpoo.platform.identityaccess.application.administration;

import com.yumpoo.platform.identityaccess.application.authorization.ManagedPlatformRole;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record IdentityMemberView(
        UUID userId,
        String displayName,
        String externalUserId,
        String email,
        String mobile,
        String departmentSummary,
        String employmentStatus,
        String accountStatus,
        Instant directorySyncedAt,
        Instant leftAt,
        Instant accountDisabledAt,
        UUID accountDisabledByUserId,
        Set<ManagedPlatformRole> platformRoles,
        long authorizationVersion,
        long rowVersion,
        String etag
) {
    public IdentityMemberView {
        platformRoles = Set.copyOf(platformRoles);
    }
}
