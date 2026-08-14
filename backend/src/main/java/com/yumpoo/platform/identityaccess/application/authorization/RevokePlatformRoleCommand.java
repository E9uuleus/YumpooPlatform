package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;

import java.util.Objects;
import java.util.UUID;

public record RevokePlatformRoleCommand(
        UUID companyId,
        UUID assignmentId,
        ManagedPlatformRole expectedRole,
        long expectedAssignmentRowVersion,
        RoleCommandActor actor,
        UUID idempotencyKey,
        RequestHash requestHash,
        String reasonReference
) {
    public RevokePlatformRoleCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(expectedRole, "expectedRole must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (expectedAssignmentRowVersion < 0) {
            throw ApplicationException.validation(new FieldViolation(
                    "expectedAssignmentRowVersion", "NON_NEGATIVE_REQUIRED",
                    "expectedAssignmentRowVersion must not be negative"));
        }
        reasonReference = GrantPlatformRoleCommand.validateReason(reasonReference);
    }
}
