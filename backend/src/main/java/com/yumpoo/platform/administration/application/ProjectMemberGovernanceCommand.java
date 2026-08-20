package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectMemberGovernanceCommand(
        CurrentActor actor, UUID projectId, UUID userId, Long expectedMembershipVersion,
        String reason, UUID idempotencyKey, RequestHash requestHash,
        String clientType, String clientVersion
) {}
