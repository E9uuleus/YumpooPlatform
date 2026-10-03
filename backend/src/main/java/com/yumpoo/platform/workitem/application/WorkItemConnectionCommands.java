package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public final class WorkItemConnectionCommands {
    private WorkItemConnectionCommands() {}
    public record Link(CurrentActor actor, UUID workItemId, UUID columnId, UUID targetWorkItemId,
            UUID idempotencyKey, RequestHash requestHash) {}
    public record CreateConnected(CurrentActor actor, UUID workItemId, UUID columnId, UUID targetProjectId,
            String title, UUID contentId, UUID idempotencyKey, RequestHash requestHash) {}
    public record Unlink(CurrentActor actor, UUID connectionId, long expectedVersion,
            UUID idempotencyKey, RequestHash requestHash) {}
}
