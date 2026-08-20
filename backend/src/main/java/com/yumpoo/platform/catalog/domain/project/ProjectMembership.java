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
}
