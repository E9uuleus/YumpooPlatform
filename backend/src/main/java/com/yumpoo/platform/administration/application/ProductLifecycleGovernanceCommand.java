package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;

import java.util.UUID;

public record ProductLifecycleGovernanceCommand(
        CurrentActor actor,
        UUID productId,
        long expectedRowVersion,
        UUID idempotencyKey,
        RequestHash requestHash
) {
}
