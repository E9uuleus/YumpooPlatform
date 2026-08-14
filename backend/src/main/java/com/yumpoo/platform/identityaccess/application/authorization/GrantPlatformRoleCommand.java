package com.yumpoo.platform.identityaccess.application.authorization;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;

import java.util.Objects;
import java.util.UUID;

public record GrantPlatformRoleCommand(
        UUID companyId,
        UUID targetUserId,
        ManagedPlatformRole role,
        long expectedTargetRowVersion,
        RoleCommandActor actor,
        UUID idempotencyKey,
        RequestHash requestHash,
        String reasonReference
) {
    public GrantPlatformRoleCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
        if (expectedTargetRowVersion < 0) {
            throw ApplicationException.validation(new FieldViolation(
                    "expectedTargetRowVersion", "NON_NEGATIVE_REQUIRED",
                    "expectedTargetRowVersion must not be negative"));
        }
        reasonReference = validateReason(reasonReference);
    }

    static String validateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApplicationException.validation(new FieldViolation(
                    "reasonReference", "REQUIRED", "reasonReference is required"));
        }
        String normalized = reason.strip();
        if (normalized.length() > 160) {
            throw ApplicationException.validation(new FieldViolation(
                    "reasonReference", "MAX_LENGTH",
                    "reasonReference must contain at most 160 characters"));
        }
        return normalized;
    }
}
