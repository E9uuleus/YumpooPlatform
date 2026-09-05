package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WorkItemModels {
    private WorkItemModels() {}

    public record WorkItemLocator(UUID workItemId, UUID projectId, UUID contentId) {}

    public record WorkItemSummary(UUID id, UUID projectId, UUID contentId, String itemNo,
            String contentName, String contentColorToken, String title, String statusCode,
            String statusCategory, String priority,
            UUID assigneeUserId, String assigneeDisplayName, UUID reporterUserId,
            String reporterDisplayName, String description, String notes,
            LocalDate timelineStartDate, LocalDate timelineEndDate, LocalDate dueDate,
            String dueTime, Instant completedAt,
            long rowVersion, String etag, WorkItemCapabilities capabilities, Instant updatedAt) {}

    public record WorkItemDetail(UUID id, UUID projectId, UUID contentId, String itemNo,
            String contentName, String contentColorToken, String title, String statusCode,
            String statusCategory, String priority,
            UUID assigneeUserId, String assigneeDisplayName, UUID reporterUserId,
            String reporterDisplayName, String description, String notes,
            LocalDate timelineStartDate, LocalDate timelineEndDate, LocalDate dueDate,
            String dueTime, Instant completedAt,
            long rowVersion, String etag, WorkItemCapabilities capabilities,
            Instant createdAt, Instant updatedAt, boolean deleted, Instant deletedAt,
            UUID deletedByUserId, String deleteReason) {}

    public record WorkItemTransitionOption(String toStatus, String displayName,
            String statusCategory, boolean requiresResolution) {}

    public record WorkItemCapabilities(boolean canEditFields, boolean canMoveInKanban,
            boolean canMoveInProjectOrder, boolean canDiscuss,
            boolean canDelete, boolean canRestore,
            List<WorkItemTransitionOption> availableTransitions) {
        public WorkItemCapabilities { availableTransitions = List.copyOf(availableTransitions); }
    }

    public record WorkItemPage(List<WorkItemSummary> items, int page, int size,
            long totalElements, int totalPages) {
        public WorkItemPage { items = List.copyOf(items); }
    }

    public record ProjectWorkItemListItem(UUID id, UUID projectId, UUID contentId,
            String contentName, String contentColorToken, String itemNo, String title, String statusCode,
            String statusCategory, String priority, UUID assigneeUserId,
            String assigneeDisplayName, LocalDate dueDate, String dueTime, Instant completedAt,
            long rowVersion, String etag,
            WorkItemCapabilities capabilities, long subitemCount, Instant updatedAt) {}

    public record WorkItemSubitemList(List<ProjectWorkItemListItem> items) {
        public WorkItemSubitemList { items = List.copyOf(items); }
    }

    public record ProjectWorkItemCursorPage(List<ProjectWorkItemListItem> items,
            String nextCursor) {
        public ProjectWorkItemCursorPage {
            items = List.copyOf(items);
            if (nextCursor != null && nextCursor.isBlank())
                throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
    }

    public record ProjectWorkItemFilterOption(String value, String label, long count) {}

    public record ProjectWorkItemFilterOptionCursorPage(
            List<ProjectWorkItemFilterOption> items, String nextCursor) {
        public ProjectWorkItemFilterOptionCursorPage { items = List.copyOf(items); }
    }
}
