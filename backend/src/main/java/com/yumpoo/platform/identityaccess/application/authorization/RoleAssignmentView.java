package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.UUID;

public record RoleAssignmentView(
        UUID assignmentId,
        UUID companyId,
        UUID userId,
        ManagedPlatformRole role,
        String scopeType,
        UUID scopeId,
        RoleAssignmentStatus status,
        Instant grantedAt,
        Instant revokedAt,
        long rowVersion
) {
}
