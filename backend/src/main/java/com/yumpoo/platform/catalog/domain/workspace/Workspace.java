package com.yumpoo.platform.catalog.domain.workspace;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Workspace(
        UUID id,
        UUID companyId,
        String code,
        String name,
        String description,
        int sortOrder,
        WorkspaceStatus status,
        long rowVersion,
        Instant createdAt,
        UUID createdByUserId,
        Instant updatedAt,
        UUID updatedByUserId
) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    public Workspace {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        code = requireCode(code);
        name = normalizeRequired(name, 80, "name");
        description = normalizeOptional(description, 500, "description");
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public Workspace updateDetails(
            String newName,
            String newDescription,
            UUID actorUserId,
            Instant now
    ) {
        return new Workspace(
                id, companyId, code, newName, newDescription, sortOrder, status,
                rowVersion + 1, createdAt, createdByUserId, now, actorUserId);
    }

    public boolean hasSameDetails(String candidateName, String candidateDescription) {
        return name.equals(normalizeRequired(candidateName, 80, "name"))
                && Objects.equals(description, normalizeOptional(candidateDescription, 500, "description"));
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
