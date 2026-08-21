package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public final class ProjectProductLinkCommands {
    private ProjectProductLinkCommands() {}

    public record Create(
            CurrentActor actor,
            UUID projectId,
            UUID productId,
            ProjectProductRelation relationType,
            boolean primary,
            UUID idempotencyKey,
            RequestHash requestHash
    ) {}

    public record ChangePrimary(
            CurrentActor actor,
            UUID projectId,
            UUID linkId,
            long expectedVersion,
            boolean primary
    ) {}

    public record Remove(
            CurrentActor actor,
            UUID projectId,
            UUID linkId,
            long expectedVersion,
            String reason,
            UUID idempotencyKey,
            RequestHash requestHash
    ) {}
}
