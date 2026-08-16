package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.UUID;

public record PlatformRoleRevokeCommand(
        UUID companyId,
        UUID assignmentId,
        PlatformRoleCode expectedRole,
        long expectedAssignmentRowVersion,
        PlatformRoleCommandActor actor,
        UUID idempotencyKey,
        String requestHash,
        String reasonReference
) {

    public PlatformRoleRevokeCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(assignmentId, "assignmentId must not be null");
        Objects.requireNonNull(expectedRole, "expectedRole must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (expectedAssignmentRowVersion < 0) {
            throw new IllegalArgumentException("expectedAssignmentRowVersion must not be negative");
        }
        requestHash = PlatformRoleGrantCommand.validateRequestHash(requestHash);
        reasonReference = PlatformRoleGrantCommand.validateReason(reasonReference);
    }
}
