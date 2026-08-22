package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record GovernanceOverrideCommand(CurrentActor actor, GovernanceOverrideAction action,
        String targetType, UUID targetId, String reason, long expectedRowVersion,
        UUID idempotencyKey, RequestHash requestHash) {
}
