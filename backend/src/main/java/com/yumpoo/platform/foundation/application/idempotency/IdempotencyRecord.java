package com.yumpoo.platform.foundation.application.idempotency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据库中已有幂等记录的应用层快照。
 */
public record IdempotencyRecord(
        UUID id,
        IdempotencyCommand command,
        IdempotencyState state,
        StoredCommandResult result,
        Instant leaseUntil,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt
) {

    public IdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }

        if (state == IdempotencyState.COMPLETED) {
            Objects.requireNonNull(result, "completed record result must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null for completed record");
            if (completedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("completedAt must not be before createdAt");
            }
        } else if (result != null || completedAt != null) {
            throw new IllegalArgumentException("incomplete record must not contain a completed result");
        }

        if (state == IdempotencyState.PROCESSING && leaseUntil == null) {
            throw new IllegalArgumentException("processing record leaseUntil must not be null");
        }
    }
}
