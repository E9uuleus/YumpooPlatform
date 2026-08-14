package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

import java.util.Objects;
import java.util.UUID;

public record RoleAssignmentQuery(
        UUID companyId,
        UUID actorUserId,
        UUID userId,
        ManagedPlatformRole role,
        RoleAssignmentStatus status,
        int page,
        int pageSize
) {
    public RoleAssignmentQuery {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        if (page < 0) {
            throw ApplicationException.validation(new FieldViolation(
                    "page", "NON_NEGATIVE_REQUIRED", "page must not be negative"));
        }
        if (pageSize < 1 || pageSize > 100) {
            throw ApplicationException.validation(new FieldViolation(
                    "pageSize", "RANGE", "pageSize must be between 1 and 100"));
        }
    }
}
