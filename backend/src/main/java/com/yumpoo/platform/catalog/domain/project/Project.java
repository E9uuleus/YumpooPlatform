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
        ProjectType projectType,
        ProjectLifecycle lifecycle,
        UUID ownerUserId,
        String templateKey,
        int templateVersion,
        String customerName,
        String customerReference,
        String deliverySite,
        String contactNote,
        long rowVersion,
        Instant createdAt,
        UUID createdByUserId,
        Instant updatedAt,
        UUID updatedByUserId,
        Instant activatedAt,
        Instant archivedAt
) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    public Project {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(projectType, "projectType must not be null");
        Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(createdByUserId, "createdByUserId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(updatedByUserId, "updatedByUserId must not be null");
        code = requireCode(code);
        name = normalizeRequired(name, 80, "name");
        description = normalizeOptional(description, 500, "description");
        templateKey = normalizeRequired(templateKey, 32, "templateKey");
        customerName = normalizeOptional(customerName, 160, "customerName");
        customerReference = normalizeOptional(customerReference, 80, "customerReference");
        deliverySite = normalizeOptional(deliverySite, 160, "deliverySite");
        contactNote = normalizeOptional(contactNote, 500, "contactNote");
        if (!projectType.templateKey().equals(templateKey)) {
            throw new IllegalArgumentException("project type and template key must match");
        }
        if (templateVersion < 1) {
            throw new IllegalArgumentException("templateVersion must be positive");
        }
        if (rowVersion < 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("project version or timestamps are invalid");
        }
        if (lifecycle == ProjectLifecycle.DRAFT && (activatedAt != null || archivedAt != null)) {
            throw new IllegalArgumentException("draft project must not have lifecycle timestamps");
        }
    }

    public static Project create(
            UUID id,
            UUID companyId,
            UUID workspaceId,
            String code,
            String name,
            String description,
            ProjectType projectType,
            UUID ownerUserId,
            String templateKey,
            int templateVersion,
            String customerName,
            String customerReference,
            String deliverySite,
            String contactNote,
            UUID actorUserId,
            Instant now
    ) {
        return new Project(id, companyId, workspaceId, code, name, description,
                projectType, ProjectLifecycle.DRAFT, ownerUserId, templateKey, templateVersion,
                customerName, customerReference, deliverySite, contactNote, 0,
                now, actorUserId, now, actorUserId, null, null);
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
        return new Project(id, companyId, workspaceId, code, name, description, projectType,
                lifecycle, newOwnerUserId, templateKey, templateVersion, customerName,
                customerReference, deliverySite, contactNote, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, activatedAt, archivedAt);
    }

    public boolean hasSameDetails(
            String nextName,
            String nextDescription,
            String nextCustomerName,
            String nextCustomerReference,
            String nextDeliverySite,
            String nextContactNote
    ) {
        return name.equals(normalizeRequired(nextName, 80, "name"))
                && Objects.equals(description, normalizeOptional(nextDescription, 500, "description"))
                && Objects.equals(customerName, normalizeOptional(nextCustomerName, 160, "customerName"))
                && Objects.equals(customerReference,
                normalizeOptional(nextCustomerReference, 80, "customerReference"))
                && Objects.equals(deliverySite, normalizeOptional(nextDeliverySite, 160, "deliverySite"))
                && Objects.equals(contactNote, normalizeOptional(nextContactNote, 500, "contactNote"));
    }

    public Project updateDetails(
            String nextName,
            String nextDescription,
            String nextCustomerName,
            String nextCustomerReference,
            String nextDeliverySite,
            String nextContactNote,
            UUID actorUserId,
            Instant now
    ) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle == ProjectLifecycle.ARCHIVED) {
            throw new IllegalStateException("archived project cannot change settings");
        }
        return new Project(id, companyId, workspaceId, code, nextName, nextDescription, projectType,
                lifecycle, ownerUserId, templateKey, templateVersion, nextCustomerName,
                nextCustomerReference, nextDeliverySite, nextContactNote, rowVersion + 1,
                createdAt, createdByUserId, now, actorUserId, activatedAt, archivedAt);
    }

    public Project activate(UUID actorUserId, Instant now) {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (lifecycle != ProjectLifecycle.DRAFT) {
            throw new IllegalStateException("only draft project can be activated");
        }
        return new Project(id, companyId, workspaceId, code, name, description, projectType,
                ProjectLifecycle.ACTIVE, ownerUserId, templateKey, templateVersion, customerName,
                customerReference, deliverySite, contactNote, rowVersion + 1, createdAt,
                createdByUserId, now, actorUserId, now, null);
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
