package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PlatformRoleGrantCommand(
        UUID companyId,
        UUID targetUserId,
        PlatformRoleCode role,
        long expectedTargetRowVersion,
        PlatformRoleCommandActor actor,
        UUID idempotencyKey,
        String requestHash,
        String reasonReference
) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public PlatformRoleGrantCommand {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (expectedTargetRowVersion < 0) {
            throw new IllegalArgumentException("expectedTargetRowVersion must not be negative");
        }
        requestHash = validateRequestHash(requestHash);
        reasonReference = validateReason(reasonReference);
    }

    static String validateRequestHash(String value) {
        Objects.requireNonNull(value, "requestHash must not be null");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "requestHash must be a 64-character lowercase SHA-256 hash"
            );
        }
        return value;
    }

    static String validateReason(String value) {
        Objects.requireNonNull(value, "reasonReference must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException(
                    "reasonReference must contain between 1 and 160 characters"
            );
        }
        return normalized;
    }
}
