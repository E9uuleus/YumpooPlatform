package com.yumpoo.platform.catalog.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProjectProductLink(
        UUID id,
        UUID companyId,
        UUID projectId,
        UUID productId,
        ProjectProductRelationType relationType,
        boolean primary,
        Instant linkedAt,
        UUID linkedByUserId,
        Instant updatedAt,
        UUID updatedByUserId,
        Instant removedAt,
        UUID removedByUserId,
        String removeReason,
        long rowVersion
) {
    public ProjectProductLink {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(relationType, "relationType must not be null");
        Objects.requireNonNull(linkedAt, "linkedAt must not be null");
        Objects.requireNonNull(linkedByUserId, "linkedByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        if (updatedAt.isBefore(linkedAt)) {
            throw new IllegalArgumentException("updatedAt must not precede linkedAt");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        removeReason = normalizeReason(removeReason);
        if (removedAt == null && (removedByUserId != null || removeReason != null)) {
            throw new IllegalArgumentException("active link must not contain removal facts");
        }
        if (removedAt != null && (removedByUserId == null || removedAt.isBefore(updatedAt))) {
            throw new IllegalArgumentException("removed link must contain valid removal facts");
        }
    }

    public static ProjectProductLink create(
            UUID id,
            UUID companyId,
            UUID projectId,
            UUID productId,
            ProjectProductRelationType relationType,
            boolean primary,
            UUID actorUserId,
            Instant now
    ) {
        return new ProjectProductLink(id, companyId, projectId, productId, relationType, primary,
                now, actorUserId, now, actorUserId, null, null, null, 0);
    }

    public ProjectProductLink changePrimary(boolean next, UUID actorUserId, Instant now) {
        if (removedAt != null) {
            throw new IllegalStateException("removed link cannot change primary flag");
        }
        if (primary == next) return this;
        return new ProjectProductLink(id, companyId, projectId, productId, relationType, next,
                linkedAt, linkedByUserId, now, actorUserId, null, null, null, rowVersion + 1);
    }

    public ProjectProductLink remove(UUID actorUserId, String reason, Instant now) {
        if (removedAt != null) {
            throw new IllegalStateException("only active link can be removed");
        }
        return new ProjectProductLink(id, companyId, projectId, productId, relationType, primary,
                linkedAt, linkedByUserId, now, actorUserId, now, actorUserId, reason,
                rowVersion + 1);
    }

    public ProjectProductLinkStatus status() {
        return removedAt == null ? ProjectProductLinkStatus.ACTIVE : ProjectProductLinkStatus.REMOVED;
    }

    private static String normalizeReason(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("removeReason length is invalid");
        }
        return normalized;
    }
}
