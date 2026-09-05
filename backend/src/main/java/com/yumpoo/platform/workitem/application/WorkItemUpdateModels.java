package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemUpdateModels {
    private WorkItemUpdateModels() {}

    public record WorkItemUpdateView(
            UUID id, UUID projectId, UUID contentId, UUID workItemId,
            UUID authorUserId, String authorDisplayName,
            String bodyHtml, String bodyText, String status,
            long rowVersion, String etag,
            Instant createdAt, Instant editedAt, UUID editedByUserId,
            Instant deletedAt, UUID deletedByUserId, UUID parentUpdateId, Instant pinnedAt, UUID pinnedByUserId,
            long replyCount, List<WorkItemUpdateView> replies, String repliesNextCursor,
            WorkItemUpdateCapabilities capabilities
    ) {}

    public record WorkItemUpdateCapabilities(
            boolean canEdit,
            boolean canDelete,
            boolean canReply,
            boolean canPin
    ) {}

    public record WorkItemUpdatePage(List<WorkItemUpdateView> items, String nextCursor, List<WorkItemUpdateView> pinnedItems) {
        public WorkItemUpdatePage {
            items = List.copyOf(items);
            pinnedItems = List.copyOf(pinnedItems);
            if (nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("nextCursor must be null or non-blank");
            }
        }
    }

    public record UpdateCursor(Instant createdAt, UUID id) {}

    public record UpdateLocator(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId, UUID updateId) {}
}
