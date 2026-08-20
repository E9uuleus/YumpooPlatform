package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record WorkspaceLifecycleCommand(
        CurrentActor actor,
        UUID workspaceId,
        long expectedRowVersion,
        UUID idempotencyKey,
        RequestHash requestHash
) {
}
