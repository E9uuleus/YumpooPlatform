package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record Content(
        UUID id, UUID companyId, UUID projectId, String code, String name, String colorToken,
        int sortOrder, boolean active, boolean protectedContent, boolean everUsed, long rowVersion,
        Instant createdAt, UUID createdByUserId, Instant updatedAt, UUID updatedByUserId,
        Instant deletedAt, UUID deletedByUserId
) {
    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final Set<String> PROTECTED_CODES = Set.of("REQUIREMENTS", "TASKS", "DEFECTS");

    public Content {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        code = requireCode(code);
        name = requireName(name);
        colorToken = requireColor(colorToken);
        if (sortOrder <= 0 || rowVersion < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("content order, version or timestamps are invalid");
        }
        if ((deletedAt == null) != (deletedByUserId == null)) {
            throw new IllegalArgumentException("content deletion facts are incomplete");
        }
        if (deletedAt != null && (protectedContent || everUsed)) {
            throw new IllegalArgumentException("protected or used content cannot be deleted");
        }
    }

    public static Content initial(UUID id, UUID companyId, UUID projectId, String code,
            String name, String colorToken, int sortOrder, UUID actorUserId, Instant now) {
        return new Content(id, companyId, projectId, code, name, colorToken, sortOrder,
                true, PROTECTED_CODES.contains(code), false, 0,
                now, actorUserId, now, actorUserId, null, null);
    }

    public static Content create(UUID id, UUID companyId, UUID projectId, String code,
            String name, String colorToken, int sortOrder, UUID actorUserId, Instant now) {
        return new Content(id, companyId, projectId, code, name, colorToken, sortOrder,
                true, false, false, 0, now, actorUserId, now, actorUserId, null, null);
    }

    public Content update(String nextName, String nextColorToken, int nextSortOrder,
            boolean nextActive, UUID actorUserId, Instant now) {
        if (deletedAt != null) throw new IllegalStateException("deleted content is read only");
        return new Content(id, companyId, projectId, code, nextName, nextColorToken,
                nextSortOrder, nextActive, protectedContent, everUsed, rowVersion + 1,
                createdAt, createdByUserId, now, actorUserId, null, null);
    }

    public Content markUsed(UUID actorUserId, Instant now) {
        if (everUsed) return this;
        return new Content(id, companyId, projectId, code, name, colorToken, sortOrder,
                active, protectedContent, true, rowVersion + 1, createdAt, createdByUserId,
                now, actorUserId, null, null);
    }

    public Content delete(UUID actorUserId, Instant now) {
        if (protectedContent || everUsed) throw new IllegalStateException("content cannot be deleted");
        return new Content(id, companyId, projectId, code, name, colorToken, sortOrder,
                false, false, false, rowVersion + 1, createdAt, createdByUserId,
                now, actorUserId, now, actorUserId);
    }

    private static String requireCode(String value) {
        Objects.requireNonNull(value, "code must not be null");
        if (!STABLE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        return value;
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 80) {
            throw new IllegalArgumentException("name length is invalid");
        }
        return normalized;
    }

    private static String requireColor(String value) {
        Objects.requireNonNull(value, "colorToken must not be null");
        if (!value.matches("^[A-Z][A-Z0-9_]{1,23}$")) {
            throw new IllegalArgumentException("colorToken is invalid");
        }
        return value;
    }
}
