package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectArchiveOperationCommand(CurrentActor actor, UUID projectId,
        long expectedRowVersion, UUID idempotencyKey, RequestHash requestHash) {
}
