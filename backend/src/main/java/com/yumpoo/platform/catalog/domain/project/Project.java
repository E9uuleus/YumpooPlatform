package com.yumpoo.platform.catalog.domain.project;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Project(
        UUID id,
        UUID companyId,
        UUID workspaceId,
        String code,
        String name,
        String description,
        ProjectLifecycle lifecycle,
        UUID ownerUserId,
        long rowVersion,
        Instant createdAt,
        UUID createdByUserId,
        Instant updatedAt,
        UUID updatedByUserId,
        Instant archivedAt
) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    public Project {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        code = requireCode(code);
        name = normalizeRequired(name, 80, "name");
        description = normalizeOptional(description, 500, "description");
        if (rowVersion < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("project version or timestamps are invalid");
        }
        if ((lifecycle == ProjectLifecycle.ARCHIVED) != (archivedAt != null)
                || (archivedAt != null && (archivedAt.isBefore(createdAt) || archivedAt.isAfter(updatedAt)))) {
            throw new IllegalArgumentException("project lifecycle timestamps are invalid");
        }
    }

    public static Project create(
            UUID id,
            UUID companyId,
            UUID workspaceId,
            String code,
            String name,
            String description,
            UUID ownerUserId,
            UUID actorUserId,
            Instant now
    ) {
        return new Project(id, companyId, workspaceId, code, name, description, ProjectLifecycle.ACTIVE, ownerUserId, 0,
                now, actorUserId, now, actorUserId, null);
    }

    public Project reassignOwner(UUID newOwnerUserId, UUID actorUserId, Instant now) {
        Objects.requireNonNull(newOwnerUserId, "newOwnerUserId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle == ProjectLifecycle.ARCHIVED) {
            throw new IllegalStateException("archived project cannot change owner");
        }
        if (ownerUserId.equals(newOwnerUserId)) {
            throw new IllegalStateException("new owner must differ from current owner");
        }
        return new Project(id, companyId, workspaceId, code, name, description,
                lifecycle, newOwnerUserId, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, archivedAt);
    }

    public boolean hasSameDetails(String nextName, String nextDescription) {
        return name.equals(normalizeRequired(nextName, 80, "name"))
                && Objects.equals(description, normalizeOptional(nextDescription, 500, "description"));
    }

    public Project updateDetails(String nextName, String nextDescription, UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle == ProjectLifecycle.ARCHIVED) {
            throw new IllegalStateException("archived project cannot change settings");
        }
        return new Project(id, companyId, workspaceId, code, nextName, nextDescription,
                lifecycle, ownerUserId, rowVersion + 1, createdAt, createdByUserId, now, actorUserId, archivedAt);
    }

    public Project archive(UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle != ProjectLifecycle.ACTIVE) {
            throw new IllegalStateException("only active project can be archived");
        }
        return new Project(id, companyId, workspaceId, code, name, description,
                ProjectLifecycle.ARCHIVED, ownerUserId, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, now);
    }

    public Project reopen(UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle != ProjectLifecycle.ARCHIVED) {
            throw new IllegalStateException("only archived project can be reopened");
        }
        return new Project(id, companyId, workspaceId, code, name, description,
                ProjectLifecycle.ACTIVE, ownerUserId, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, null);
    }

    private static String requireCode(String value) {
        Objects.requireNonNull(value, "code must not be null");
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        return value;
    }

    private static String normalizeRequired(String value, int maximum, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return normalized;
    }
}
