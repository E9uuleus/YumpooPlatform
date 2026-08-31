package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkItemRelation(
        UUID id,
        UUID companyId,
        WorkItemRelationType relationType,
        UUID leftWorkItemId,
        UUID rightWorkItemId,
        UUID leftProjectId,
        UUID rightProjectId,
        UUID createdByUserId,
        Instant createdAt,
        UUID deletedByUserId,
        Instant deletedAt,
        String deleteReason,
        long rowVersion
) {
    public WorkItemRelation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companyId);
        Objects.requireNonNull(relationType);
        Objects.requireNonNull(leftWorkItemId);
        Objects.requireNonNull(rightWorkItemId);
        Objects.requireNonNull(leftProjectId);
        Objects.requireNonNull(rightProjectId);
        Objects.requireNonNull(createdByUserId);
        Objects.requireNonNull(createdAt);
        if (leftWorkItemId.equals(rightWorkItemId)) throw new IllegalArgumentException("relation endpoints must differ");
        if (relationType == WorkItemRelationType.PARENT_CHILD && !leftProjectId.equals(rightProjectId))
            throw new IllegalArgumentException("parent and child must belong to the same project");
        if (rowVersion < 0) throw new IllegalArgumentException("rowVersion must not be negative");
        boolean active = deletedAt == null && deletedByUserId == null && deleteReason == null;
        boolean deleted = deletedAt != null && deletedByUserId != null
                && deleteReason != null && !deleteReason.isBlank() && deleteReason.length() <= 500;
        if (!active && !deleted) throw new IllegalArgumentException("delete facts must be complete");
    }

    public static WorkItemRelation create(UUID id, UUID companyId, WorkItemRelationType relationType,
            UUID leftWorkItemId, UUID rightWorkItemId, UUID leftProjectId, UUID rightProjectId,
            UUID actorUserId, Instant now) {
        return new WorkItemRelation(id, companyId, relationType, leftWorkItemId, rightWorkItemId,
                leftProjectId, rightProjectId, actorUserId, now, null, null, null, 0);
    }

    public boolean active() { return deletedAt == null; }

    public WorkItemRelation delete(UUID actorUserId, String reason, Instant now) {
        if (!active()) throw new IllegalStateException("relation is not active");
        String normalized = Objects.requireNonNull(reason).strip();
        if (normalized.isEmpty() || normalized.length() > 500)
            throw new IllegalArgumentException("delete reason length must be between 1 and 500");
        return new WorkItemRelation(id, companyId, relationType, leftWorkItemId, rightWorkItemId,
                leftProjectId, rightProjectId, createdByUserId, createdAt, actorUserId, now,
                normalized, rowVersion + 1);
    }
}
