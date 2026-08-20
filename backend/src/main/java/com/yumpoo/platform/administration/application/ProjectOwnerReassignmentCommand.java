package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectOwnerReassignmentCommand(
        CurrentActor actor, UUID projectId, long expectedProjectVersion, UUID newOwnerUserId,
        String reason, UUID idempotencyKey, RequestHash requestHash,
        String clientType, String clientVersion
) {}
