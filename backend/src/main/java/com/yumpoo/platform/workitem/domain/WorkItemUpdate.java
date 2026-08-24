package com.yumpoo.platform.workitem.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkItemUpdate(
        UUID id, UUID companyId, UUID projectId, UUID contentId, UUID workItemId,
        UUID authorUserId, String authorDisplayName, String bodyHtml, String bodyText,
        WorkItemUpdateStatus status, Instant editDeadlineAt, long rowVersion,
        Instant createdAt, Instant editedAt, UUID editedByUserId,
        Instant deletedAt, UUID deletedByUserId, String deleteReason
) {
    public static final Duration EDIT_WINDOW = Duration.ofMinutes(15);

    public WorkItemUpdate {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(contentId, "contentId must not be null");
        Objects.requireNonNull(workItemId, "workItemId must not be null");
        Objects.requireNonNull(authorUserId, "authorUserId must not be null");
        authorDisplayName = required(authorDisplayName, 200, "authorDisplayName");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(editDeadlineAt, "editDeadlineAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (rowVersion < 0 || !editDeadlineAt.equals(createdAt.plus(EDIT_WINDOW))) {
            throw new IllegalArgumentException("update version or edit deadline is invalid");
        }
        if (status != WorkItemUpdateStatus.DELETED) {
            bodyHtml = required(bodyHtml, 65_536, "bodyHtml");
            bodyText = required(bodyText, 16_384, "bodyText");
        } else if (bodyHtml != null || bodyText != null) {
            throw new IllegalArgumentException("deleted update body must be null");
        }
    }

    public static WorkItemUpdate published(UUID id, UUID companyId, UUID projectId,
            UUID contentId, UUID workItemId, UUID authorUserId, String authorDisplayName,
            String bodyHtml, String bodyText, Instant now) {
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId,
                authorUserId, authorDisplayName, bodyHtml, bodyText,
                WorkItemUpdateStatus.PUBLISHED, now.plus(EDIT_WINDOW), 0, now,
                null, null, null, null, null);
    }

    private static String required(String value, int max, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return value;
    }
}
