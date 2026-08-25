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
            Instant editDeadlineAt, long rowVersion, String etag,
            Instant createdAt, Instant editedAt, UUID editedByUserId,
            Instant deletedAt, UUID deletedByUserId, String deleteReason,
            WorkItemUpdateCapabilities capabilities
    ) {}

    public record WorkItemUpdateCapabilities(
            boolean canEdit,
            boolean canSelfDelete,
            boolean canModerateDelete
    ) {}

    public record WorkItemUpdatePage(List<WorkItemUpdateView> items, String nextCursor) {
        public WorkItemUpdatePage {
            items = List.copyOf(items);
            if (nextCursor != null && nextCursor.isBlank()) {
                throw new IllegalArgumentException("nextCursor must be null or non-blank");
            }
        }
    }

    public record UpdateCursor(Instant createdAt, UUID id) {}

    public record UpdateLocator(UUID companyId, UUID projectId, UUID contentId,
            UUID workItemId, UUID updateId) {}
}
