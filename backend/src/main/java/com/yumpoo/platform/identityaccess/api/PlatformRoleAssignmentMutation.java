package com.yumpoo.platform.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlatformRoleAssignmentMutation(
        UUID assignmentId,
        UUID companyId,
        UUID userId,
        PlatformRoleCode role,
        PlatformRoleAssignmentStatus status,
        long assignmentRowVersion,
        long userRowVersion,
        long authorizationVersion,
        Instant changedAt
) {

    public PlatformRoleAssignmentMutation {
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (assignmentRowVersion < 0 || userRowVersion < 0 || authorizationVersion < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }
}
