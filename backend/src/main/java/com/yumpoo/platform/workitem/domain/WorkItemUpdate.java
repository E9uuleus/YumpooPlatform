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
        boolean edited = editedAt != null || editedByUserId != null;
        if (status == WorkItemUpdateStatus.PUBLISHED && edited) {
            throw new IllegalArgumentException("published update must not contain edit facts");
        }
        if (status == WorkItemUpdateStatus.EDITED && !edited) {
            throw new IllegalArgumentException("edited update must contain edit facts");
        }
        if (edited && (editedAt == null || !authorUserId.equals(editedByUserId)
                || editedAt.isBefore(createdAt) || !editedAt.isBefore(editDeadlineAt))) {
            throw new IllegalArgumentException("update edit facts are invalid");
        }
        boolean deleted = deletedAt != null || deletedByUserId != null || deleteReason != null;
        if (status != WorkItemUpdateStatus.DELETED && deleted) {
            throw new IllegalArgumentException("active update must not contain delete facts");
        }
        if (status == WorkItemUpdateStatus.DELETED) {
            if (deletedAt == null || deletedByUserId == null || deletedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("deleted update facts are invalid");
            }
            if (deleteReason == null) {
                if (!deletedByUserId.equals(authorUserId) || !deletedAt.isBefore(editDeadlineAt)) {
                    throw new IllegalArgumentException("self delete facts are invalid");
                }
            } else {
                deleteReason = normalizedReason(deleteReason);
            }
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

    public WorkItemUpdate edit(String nextBodyHtml, String nextBodyText, UUID actorUserId, Instant now) {
        requireMutable();
        if (!authorUserId.equals(actorUserId)) throw new IllegalStateException("UPDATE_EDIT_FORBIDDEN");
        requireWindow(now, "UPDATE_EDIT_WINDOW_EXPIRED");
        String normalizedHtml = required(nextBodyHtml, 65_536, "bodyHtml");
        String normalizedText = required(nextBodyText, 16_384, "bodyText");
        if (bodyHtml.equals(normalizedHtml) && bodyText.equals(normalizedText)) return this;
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId,
                authorUserId, authorDisplayName, normalizedHtml, normalizedText,
                WorkItemUpdateStatus.EDITED, editDeadlineAt, rowVersion + 1, createdAt,
                now, actorUserId, null, null, null);
    }

    public WorkItemUpdate selfDelete(UUID actorUserId, Instant now) {
        requireMutable();
        if (!authorUserId.equals(actorUserId)) throw new IllegalStateException("UPDATE_SELF_DELETE_FORBIDDEN");
        requireWindow(now, "UPDATE_SELF_DELETE_WINDOW_EXPIRED");
        return deleted(actorUserId, now, null);
    }

    public WorkItemUpdate moderateDelete(UUID actorUserId, String reason, Instant now) {
        requireMutable();
        return deleted(actorUserId, now, normalizedReason(reason));
    }

    private WorkItemUpdate deleted(UUID actorUserId, Instant now, String reason) {
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId,
                authorUserId, authorDisplayName, null, null, WorkItemUpdateStatus.DELETED,
                editDeadlineAt, rowVersion + 1, createdAt, editedAt, editedByUserId,
                now, actorUserId, reason);
    }

    private void requireMutable() {
        if (status == WorkItemUpdateStatus.DELETED) {
            throw new IllegalStateException("UPDATE_ALREADY_DELETED");
        }
    }

    private void requireWindow(Instant now, String reason) {
        Objects.requireNonNull(now, "now must not be null");
        if (now.isBefore(createdAt) || !now.isBefore(editDeadlineAt)) {
            throw new IllegalStateException(reason);
        }
    }

    private static String required(String value, int max, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return value;
    }

    private static String normalizedReason(String value) {
        Objects.requireNonNull(value, "deleteReason must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("deleteReason length is invalid");
        }
        return normalized;
    }
}
