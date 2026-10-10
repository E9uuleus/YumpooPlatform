package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.UUID;

public record ProjectDeletionOperation(CurrentActor actor, UUID projectId, long expectedVersion,
        UUID idempotencyKey, RequestHash requestHash, String confirmationCode) {}
