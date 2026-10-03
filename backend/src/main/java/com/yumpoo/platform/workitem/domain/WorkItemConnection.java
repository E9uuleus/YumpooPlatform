package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkItemConnection(UUID id, UUID companyId, UUID columnId, UUID sourceProjectId,
        UUID sourceWorkItemId, UUID targetProjectId, UUID targetWorkItemId, Origin origin,
        UUID createdByUserId, Instant createdAt, UUID deletedByUserId, Instant deletedAt,
        DeleteReason deleteReason, long rowVersion) {
    public static final int MAX_PER_CELL = 50;
    public enum Origin { LINKED, CREATED }
    public enum DeleteReason { UNLINKED, COLUMN_DELETED }

    public WorkItemConnection {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companyId);
        Objects.requireNonNull(columnId);
        Objects.requireNonNull(sourceProjectId);
        Objects.requireNonNull(sourceWorkItemId);
        Objects.requireNonNull(targetProjectId);
        Objects.requireNonNull(targetWorkItemId);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(createdByUserId);
        Objects.requireNonNull(createdAt);
        if (sourceProjectId.equals(targetProjectId))
            throw new IllegalArgumentException("connection endpoints must belong to different projects");
        if (rowVersion < 0 || (deletedAt != null && deletedAt.isBefore(createdAt)))
            throw new IllegalArgumentException("invalid version or timestamps");
        if (!(deletedAt == null && deletedByUserId == null && deleteReason == null)
                && !(deletedAt != null && deletedByUserId != null && deleteReason != null))
            throw new IllegalArgumentException("incomplete deletion facts");
    }

    public boolean active() { return deletedAt == null; }

    public static WorkItemConnection create(UUID id, UUID companyId, UUID columnId, UUID sourceProjectId,
            UUID sourceWorkItemId, UUID targetProjectId, UUID targetWorkItemId, Origin origin,
            UUID actor, Instant now) {
        return new WorkItemConnection(id, companyId, columnId, sourceProjectId, sourceWorkItemId,
                targetProjectId, targetWorkItemId, origin, actor, now, null, null, null, 0);
    }

    public WorkItemConnection delete(UUID actor, DeleteReason reason, Instant now) {
        if (!active()) throw new IllegalStateException("connection is deleted");
        return new WorkItemConnection(id, companyId, columnId, sourceProjectId, sourceWorkItemId,
                targetProjectId, targetWorkItemId, origin, createdByUserId, createdAt,
                actor, now, Objects.requireNonNull(reason), rowVersion + 1);
    }
}
