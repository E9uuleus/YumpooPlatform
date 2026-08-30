package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.UUID;

public final class WorkItemRelationCommands {
    private WorkItemRelationCommands() {}

    public record Create(CurrentActor actor, UUID currentWorkItemId,
            String relationType, String currentRole,
            UUID targetWorkItemId, UUID idempotencyKey, RequestHash requestHash) {}

    public record ChangeParent(CurrentActor actor, UUID relationId, long expectedVersion,
            UUID newParentWorkItemId, String reason, UUID idempotencyKey,
            RequestHash requestHash) {}

    public record Delete(CurrentActor actor, UUID relationId, long expectedVersion,
            String reason, UUID idempotencyKey, RequestHash requestHash) {}
}
