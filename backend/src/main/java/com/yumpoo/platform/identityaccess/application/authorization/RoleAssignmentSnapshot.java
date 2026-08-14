package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignmentSnapshot(
        UUID assignmentId,
        UUID companyId,
        UUID userId,
        ManagedPlatformRole role,
        RoleAssignmentStatus status,
        long rowVersion,
        Instant grantedAt
) {
}
