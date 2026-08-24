package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public final class WorkItemUpdateCommands {
    private WorkItemUpdateCommands() {}

    public record Publish(CurrentActor actor, UUID workItemId, String bodyHtml,
            UUID idempotencyKey, RequestHash requestHash) {}
}
