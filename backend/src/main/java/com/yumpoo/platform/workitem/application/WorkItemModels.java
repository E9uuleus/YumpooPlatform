package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemModels {
    private WorkItemModels() {}

    public record WorkItemLocator(UUID workItemId, UUID projectId, UUID contentId) {}

    public record WorkItemSummary(UUID id, UUID projectId, UUID contentId, String itemNo,
            String type, String title, String statusCode, String statusCategory, String priority,
            UUID reporterUserId, String reporterDisplayName, Instant updatedAt) {}

    public record WorkItemDetail(UUID id, UUID projectId, UUID contentId, String itemNo,
            String type, String title, String statusCode, String statusCategory, String priority,
            UUID reporterUserId, String reporterDisplayName, String description, String notes,
            Instant createdAt, Instant updatedAt) {}

    public record WorkItemPage(List<WorkItemSummary> items, int page, int size,
            long totalElements, int totalPages) {
        public WorkItemPage { items = List.copyOf(items); }
    }
}
