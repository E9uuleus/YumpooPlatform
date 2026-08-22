package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record Content(
        UUID id,
        UUID companyId,
        UUID projectId,
        String code,
        String name,
        String description,
        ContentWorkItemType workItemType,
        ContentStatus status,
        ContentViewType defaultViewType,
        String viewConfigJson,
        String appliedTemplateKey,
        int appliedTemplateVersion,
        String appliedBlueprintCode,
        long rowVersion,
        Instant createdAt,
        UUID createdByUserId,
        Instant updatedAt,
        UUID updatedByUserId,
        Instant archivedAt,
        UUID archivedByUserId
) {

    private static final Pattern STABLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    public Content {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(workItemType, "workItemType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(defaultViewType, "defaultViewType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        code = requireCode(code, "code");
        name = requireName(name);
        appliedTemplateKey = requireCode(appliedTemplateKey, "appliedTemplateKey");
        appliedBlueprintCode = requireCode(appliedBlueprintCode, "appliedBlueprintCode");
        if (appliedTemplateVersion < 1 || rowVersion < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("content template, version or timestamps are invalid");
        }
        description = normalizeDescription(description);
        if (viewConfigJson == null || viewConfigJson.isBlank())
            throw new IllegalArgumentException("viewConfigJson must not be blank");
        if (status == ContentStatus.ACTIVE && (archivedAt != null || archivedByUserId != null)) {
            throw new IllegalArgumentException("active content must not contain archive facts");
        }
        if (status == ContentStatus.ARCHIVED && (archivedAt == null || archivedByUserId == null)) {
            throw new IllegalArgumentException("archived content must contain archive facts");
        }
    }

    public static Content initial(
            UUID id,
            UUID companyId,
            UUID projectId,
            String code,
            String name,
            ContentWorkItemType workItemType,
            ContentViewType defaultViewType,
            String templateKey,
            int templateVersion,
            String blueprintCode,
            UUID actorUserId,
            Instant now
    ) {
        return new Content(id, companyId, projectId, code, name, null, workItemType,
                ContentStatus.ACTIVE, defaultViewType, "{}", templateKey, templateVersion,
                blueprintCode, 0, now, actorUserId, now, actorUserId, null, null);
    }

    public static Content create(UUID id, UUID companyId, UUID projectId, String code,
            String name, String description, ContentWorkItemType workItemType,
            ContentViewType defaultViewType, String viewConfigJson, String templateKey,
            int templateVersion, String blueprintCode, UUID actorUserId, Instant now) {
        return new Content(id, companyId, projectId, code, name, description, workItemType,
                ContentStatus.ACTIVE, defaultViewType, viewConfigJson, templateKey,
                templateVersion, blueprintCode, 0, now, actorUserId, now, actorUserId,
                null, null);
    }

    public Content update(String nextName, String nextDescription, ContentViewType nextViewType,
            String nextViewConfigJson, UUID actorUserId, Instant now) {
        if (status != ContentStatus.ACTIVE) throw new IllegalStateException("archived content is read only");
        return new Content(id, companyId, projectId, code, nextName, nextDescription, workItemType,
                status, nextViewType, nextViewConfigJson, appliedTemplateKey,
                appliedTemplateVersion, appliedBlueprintCode, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, null, null);
    }

    public Content archive(UUID actorUserId, Instant now) {
        if (status != ContentStatus.ACTIVE) throw new IllegalStateException("content is not active");
        return new Content(id, companyId, projectId, code, name, description, workItemType,
                ContentStatus.ARCHIVED, defaultViewType, viewConfigJson, appliedTemplateKey,
                appliedTemplateVersion, appliedBlueprintCode, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, now, actorUserId);
    }

    public Content restore(UUID actorUserId, Instant now) {
        if (status != ContentStatus.ARCHIVED) throw new IllegalStateException("content is not archived");
        return new Content(id, companyId, projectId, code, name, description, workItemType,
                ContentStatus.ACTIVE, defaultViewType, viewConfigJson, appliedTemplateKey,
                appliedTemplateVersion, appliedBlueprintCode, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, null, null);
    }

    private static String requireCode(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!STABLE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a stable uppercase identifier");
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

    private static String normalizeDescription(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 500)
            throw new IllegalArgumentException("description length is invalid");
        return normalized;
    }
}
