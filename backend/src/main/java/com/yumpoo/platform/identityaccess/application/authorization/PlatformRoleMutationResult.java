package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.UUID;

public record PlatformRoleMutationResult(
        UUID assignmentId,
        UUID companyId,
        UUID userId,
        ManagedPlatformRole role,
        RoleAssignmentStatus status,
        long assignmentRowVersion,
        long userRowVersion,
        long authorizationVersion,
        Instant changedAt
) {
}
