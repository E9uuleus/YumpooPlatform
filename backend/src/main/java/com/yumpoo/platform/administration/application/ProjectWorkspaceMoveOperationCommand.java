package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProjectWorkspaceMoveOperationCommand(CurrentActor actor, UUID projectId,
        UUID targetWorkspaceId, String reason, long expectedRowVersion,
        UUID idempotencyKey, RequestHash requestHash) {
}
