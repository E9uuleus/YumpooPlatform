package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WorkItemModels {
    private WorkItemModels() {}

    public record WorkItemLocator(UUID workItemId, UUID projectId, UUID contentId) {}

    public record WorkItemSummary(UUID id, UUID projectId, UUID contentId, String itemNo,
            String type, String title, String statusCode, String statusCategory, String priority,
            UUID assigneeUserId, String assigneeDisplayName, UUID reporterUserId,
            String reporterDisplayName, String description, String notes,
            LocalDate timelineStartDate, LocalDate timelineEndDate, LocalDate dueDate,
            Instant updatedAt) {}

    public record WorkItemDetail(UUID id, UUID projectId, UUID contentId, String itemNo,
            String type, String title, String statusCode, String statusCategory, String priority,
            UUID assigneeUserId, String assigneeDisplayName, UUID reporterUserId,
            String reporterDisplayName, String description, String notes,
            LocalDate timelineStartDate, LocalDate timelineEndDate, LocalDate dueDate,
            long rowVersion, String etag, WorkItemCapabilities capabilities,
            Instant createdAt, Instant updatedAt) {}

    public record WorkItemTransitionOption(String toStatus, String displayName,
            String statusCategory, boolean requiresResolution) {}

    public record WorkItemCapabilities(boolean canEditFields,
            List<WorkItemTransitionOption> availableTransitions) {
        public WorkItemCapabilities { availableTransitions = List.copyOf(availableTransitions); }
    }

    public record WorkItemPage(List<WorkItemSummary> items, int page, int size,
            long totalElements, int totalPages) {
        public WorkItemPage { items = List.copyOf(items); }
    }
}
