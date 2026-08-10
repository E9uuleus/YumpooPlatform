package com.yumpoo.platform.foundation.application.idempotency;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyRecordPort {

    IdempotencyClaim claim(
            UUID recordId,
            IdempotencyCommand command,
            Instant createdAt,
            Instant leaseUntil,
            Instant expiresAt
    );

    void complete(
            UUID recordId,
            StoredCommandResult result,
            Instant completedAt,
            Instant expiresAt
    );
}
