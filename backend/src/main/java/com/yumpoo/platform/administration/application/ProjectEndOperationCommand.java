package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import java.util.UUID;

public record ProjectEndOperationCommand(CurrentActor actor, UUID projectId, long expectedRowVersion,
        UUID idempotencyKey, RequestHash requestHash, boolean ended) {}
