package com.yumpoo.platform.catalog.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectMembership(
        UUID id,
        UUID companyId,
        UUID projectId,
        UUID userId,
        ProjectMembershipStatus status,
        Instant joinedAt,
        UUID joinedByUserId,
        Instant removedAt,
        UUID removedByUserId,
        String removeReason,
        long rowVersion
) {
    public ProjectMembership {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(joinedAt, "joinedAt must not be null");
        Objects.requireNonNull(joinedByUserId, "joinedByUserId must not be null");
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        if (status == ProjectMembershipStatus.ACTIVE
                && (removedAt != null || removedByUserId != null || removeReason != null)) {
            throw new IllegalArgumentException("active membership must not contain removal facts");
        }
        if (status == ProjectMembershipStatus.REMOVED
                && (removedAt == null || removedByUserId == null || removedAt.isBefore(joinedAt))) {
            throw new IllegalArgumentException("removed membership must contain valid removal facts");
        }
        removeReason = normalizeReason(removeReason);
    }

    public static ProjectMembership activeOwner(
            UUID id,
            UUID companyId,
            UUID projectId,
            UUID ownerUserId,
            UUID actorUserId,
            Instant now
    ) {
        return new ProjectMembership(id, companyId, projectId, ownerUserId,
                ProjectMembershipStatus.ACTIVE, now, actorUserId,
                null, null, null, 0);
    }

    public static ProjectMembership activeMember(
            UUID id, UUID companyId, UUID projectId, UUID userId, UUID actorUserId, Instant now
    ) {
        return new ProjectMembership(id, companyId, projectId, userId,
                ProjectMembershipStatus.ACTIVE, now, actorUserId, null, null, null, 0);
    }

    public ProjectMembership reactivate(UUID actorUserId, Instant now) {
        if (status != ProjectMembershipStatus.REMOVED) {
            throw new IllegalStateException("only removed membership can be reactivated");
        }
        return new ProjectMembership(id, companyId, projectId, userId,
                ProjectMembershipStatus.ACTIVE, now, actorUserId, null, null, null, rowVersion + 1);
    }

    public ProjectMembership remove(UUID actorUserId, String reason, Instant now) {
        if (status != ProjectMembershipStatus.ACTIVE) {
            throw new IllegalStateException("only active membership can be removed");
        }
        return new ProjectMembership(id, companyId, projectId, userId,
                ProjectMembershipStatus.REMOVED, joinedAt, joinedByUserId,
                now, actorUserId, reason, rowVersion + 1);
    }

    private static String normalizeReason(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("removeReason length is invalid");
        }
        return normalized;
    }
}
