package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record WorkspaceCreateCommand(
        CurrentActor actor,
        String code,
        String name,
        String description,
        int sortOrder,
        UUID idempotencyKey,
        RequestHash requestHash
) {
}
