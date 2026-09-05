package com.yumpoo.platform.workitem.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkItemUpdate(
        UUID id, UUID companyId, UUID projectId, UUID contentId, UUID workItemId,
        UUID authorUserId, String authorDisplayName, String bodyHtml, String bodyText,
        WorkItemUpdateStatus status, long rowVersion,
        Instant createdAt, Instant editedAt, UUID editedByUserId,
        Instant deletedAt, UUID deletedByUserId,
        UUID parentUpdateId, Instant pinnedAt, UUID pinnedByUserId
) {
    public WorkItemUpdate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(companyId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(contentId);
        Objects.requireNonNull(workItemId);
        Objects.requireNonNull(authorUserId);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(status);
        authorDisplayName = required(authorDisplayName, 200);
        if (rowVersion < 0) throw new IllegalArgumentException("invalid version");
        if (status != WorkItemUpdateStatus.DELETED) {
            bodyHtml = required(bodyHtml, 65_536);
            bodyText = required(bodyText, 16_384);
            if (deletedAt != null || deletedByUserId != null) throw new IllegalArgumentException("invalid deletion");
        } else if (bodyHtml != null || bodyText != null || deletedAt == null
                || deletedByUserId == null || deletedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("invalid deletion");
        }
        if ((editedAt == null) != (editedByUserId == null)
                || (editedAt != null && (!authorUserId.equals(editedByUserId) || editedAt.isBefore(createdAt)))
                || (status == WorkItemUpdateStatus.PUBLISHED && editedAt != null)
                || (status == WorkItemUpdateStatus.EDITED && editedAt == null)) {
            throw new IllegalArgumentException("invalid edit facts");
        }
        if ((pinnedAt == null) != (pinnedByUserId == null)
                || (pinnedAt != null && (parentUpdateId != null || status == WorkItemUpdateStatus.DELETED
                    || pinnedAt.isBefore(createdAt)))) throw new IllegalArgumentException("invalid pin facts");
        if (id.equals(parentUpdateId)) throw new IllegalArgumentException("invalid parent");
    }

    public static WorkItemUpdate published(UUID id, UUID companyId, UUID projectId,
            UUID contentId, UUID workItemId, UUID authorUserId, String authorDisplayName,
            String bodyHtml, String bodyText, Instant now) {
        return published(id, companyId, projectId, contentId, workItemId, authorUserId,
                authorDisplayName, bodyHtml, bodyText, now, null);
    }

    public static WorkItemUpdate published(UUID id, UUID companyId, UUID projectId,
            UUID contentId, UUID workItemId, UUID authorUserId, String authorDisplayName,
            String bodyHtml, String bodyText, Instant now, UUID parentUpdateId) {
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId, authorUserId,
                authorDisplayName, bodyHtml, bodyText, WorkItemUpdateStatus.PUBLISHED, 0, now,
                null, null, null, null, parentUpdateId, null, null);
    }

    public WorkItemUpdate edit(String html, String text, UUID actor, Instant now) {
        requireMutable(now);
        if (!authorUserId.equals(actor)) throw new IllegalStateException("UPDATE_EDIT_FORBIDDEN");
        html = required(html, 65_536);
        text = required(text, 16_384);
        if (bodyHtml.equals(html) && bodyText.equals(text)) return this;
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId, authorUserId,
                authorDisplayName, html, text, WorkItemUpdateStatus.EDITED, rowVersion + 1,
                createdAt, now, actor, null, null, parentUpdateId, pinnedAt, pinnedByUserId);
    }

    public WorkItemUpdate delete(UUID actor, Instant now) {
        requireMutable(now);
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId, authorUserId,
                authorDisplayName, null, null, WorkItemUpdateStatus.DELETED, rowVersion + 1,
                createdAt, editedAt, editedByUserId, now, actor, parentUpdateId, null, null);
    }

    public WorkItemUpdate pin(boolean pinned, UUID actor, Instant now) {
        requireMutable(now);
        if (parentUpdateId != null) throw new IllegalStateException("UPDATE_REPLY_PIN_FORBIDDEN");
        if (pinned == (pinnedAt != null)) return this;
        return new WorkItemUpdate(id, companyId, projectId, contentId, workItemId, authorUserId,
                authorDisplayName, bodyHtml, bodyText, status, rowVersion + 1, createdAt,
                editedAt, editedByUserId, null, null, null, pinned ? now : null, pinned ? actor : null);
    }

    private void requireMutable(Instant now) {
        if (status == WorkItemUpdateStatus.DELETED) throw new IllegalStateException("UPDATE_ALREADY_DELETED");
        if (now.isBefore(createdAt)) throw new IllegalStateException("UPDATE_INVALID_TIME");
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid text");
        return value;
    }
}
