package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public final class WorkItemCommands {
    private WorkItemCommands() {}

    public record Create(CurrentActor actor, UUID contentId, String title, String priority,
            String description, String notes, UUID idempotencyKey, RequestHash requestHash) {}
}
