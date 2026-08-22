package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.time.LocalDate;
import java.util.UUID;

public final class WorkItemCommands {
    private WorkItemCommands() {}

    public record Create(CurrentActor actor, UUID contentId, String title, String priority,
            UUID assigneeUserId, String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, UUID idempotencyKey,
            RequestHash requestHash) {}

    public record Update(CurrentActor actor, UUID workItemId, long expectedVersion,
            String title, String priority, UUID assigneeUserId, String description,
            String notes, LocalDate timelineStartDate, LocalDate timelineEndDate,
            LocalDate dueDate) {}

    public record Transition(CurrentActor actor, UUID workItemId, long expectedVersion,
            String toStatus, String resolution, UUID idempotencyKey, RequestHash requestHash) {}
}
