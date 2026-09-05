package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.time.LocalDate;
import java.util.UUID;

public final class WorkItemCommands {
    private WorkItemCommands() {}

    public record Create(CurrentActor actor, UUID projectId, UUID contentId, String title, String priority,
            UUID assigneeUserId, String description, String notes, LocalDate timelineStartDate,
            LocalDate timelineEndDate, LocalDate dueDate, UUID idempotencyKey,
            RequestHash requestHash, DueTimeChange dueTime) {}

    public record CreateSubitem(CurrentActor actor, UUID parentWorkItemId, UUID contentId,
            String title, String priority, UUID assigneeUserId, String description, String notes,
            LocalDate timelineStartDate, LocalDate timelineEndDate, LocalDate dueDate,
            UUID idempotencyKey, RequestHash requestHash, DueTimeChange dueTime) {}

    public record Update(CurrentActor actor, UUID workItemId, long expectedVersion,
            String title, String priority, UUID assigneeUserId, String description,
            String notes, LocalDate timelineStartDate, LocalDate timelineEndDate,
            LocalDate dueDate, DueTimeChange dueTime) {}

    public record Transition(CurrentActor actor, UUID workItemId, long expectedVersion,
            String toStatus, String resolution, UUID idempotencyKey, RequestHash requestHash) {}

    public record RankMove(CurrentActor actor, UUID workItemId, long expectedVersion,
            String toStatus, String placement, UUID anchorWorkItemId, String resolution,
            UUID idempotencyKey, RequestHash requestHash) {}

    public record ProjectOrderMove(CurrentActor actor, UUID projectId, UUID workItemId,
            long expectedVersion, UUID previousVisibleWorkItemId, UUID nextVisibleWorkItemId,
            UUID idempotencyKey, RequestHash requestHash) {}

    public record SubitemOrderMove(CurrentActor actor, UUID parentWorkItemId, UUID subitemId,
            long expectedVersion, UUID previousVisibleWorkItemId, UUID nextVisibleWorkItemId,
            UUID idempotencyKey, RequestHash requestHash) {}

    public record InlineUpdate(CurrentActor actor, UUID workItemId, long expectedVersion,
            String field, String priority, UUID assigneeUserId, LocalDate dueDate,
            UUID idempotencyKey, RequestHash requestHash, DueTimeChange dueTime) {}

    public record ChangeContent(CurrentActor actor, UUID workItemId, long expectedVersion,
            UUID contentId, UUID idempotencyKey, RequestHash requestHash) {}

    public record Delete(CurrentActor actor, UUID workItemId, long expectedVersion,
            String reason, UUID idempotencyKey, RequestHash requestHash) {}

    public record Restore(CurrentActor actor, UUID workItemId, long expectedVersion,
            UUID idempotencyKey, RequestHash requestHash) {}
}
