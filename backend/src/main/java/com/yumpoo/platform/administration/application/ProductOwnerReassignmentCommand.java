package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;

import java.util.UUID;

public record ProductOwnerReassignmentCommand(
        CurrentActor actor,
        UUID productId,
        long expectedRowVersion,
        UUID newOwnerUserId,
        String reason,
        UUID idempotencyKey,
        RequestHash requestHash,
        String clientType,
        String clientVersion
) {
}
