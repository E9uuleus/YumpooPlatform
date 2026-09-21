package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkItemStatisticsQuery {
    Snapshot aggregate(CurrentActor actor, List<UUID> connectedProjectIds, Filters filters);
    Page items(CurrentActor actor, List<UUID> connectedProjectIds, Filters filters, int offset, int limit);
    void validate(Filters filters);
    void validateChart(ChartRequest chart);
    List<ChartResult> charts(CurrentActor actor, List<UUID> connectedProjectIds, Filters filters, List<ChartRequest> charts, Instant asOf);
    Page chartItems(CurrentActor actor, List<UUID> connectedProjectIds, Filters filters, ChartRequest chart, ChartSelection selection, int offset, int limit);

    TablePage table(CurrentActor actor, List<UUID> connectedProjectIds, Filters filters, ChartRequest chart,
            ChartSelection selection, UUID projectId, TableCriteria criteria);
    record TableCriteria(String q, List<String> status, List<String> priority, List<UUID> assigneeUserId,
            List<UUID> contentId, LocalDate dueFrom, LocalDate dueTo, Instant updatedAfter, List<String> sort,
            String timeTrackingState, Long timeTrackingMinMs, Long timeTrackingMaxMs, String emptyField,
            String cursor, Integer limit, String field, UUID parentWorkItemId) {}
    record TablePage(List<com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemListItem> items,
            List<com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemFilterOption> options,
            String nextCursor, List<UUID> contextIds, java.util.Map<UUID, Long> subitemCounts) {}

    record ChartMeasure(String metric, String calculation) {}
    record ChartRequest(String id, String dimension, String series, String dateInterval, String timezone,
            ChartMeasure measure, ChartMeasure xMeasure, ChartMeasure sizeMeasure, Filters filters, List<UUID> projectIds, boolean showEmpty) {}
    record ChartSelection(String key, String seriesKey) {}
    record ChartPoint(String key, String label, String colorToken, String seriesKey, String seriesLabel,
            String seriesColorToken, long count, double value, double xValue, double sizeValue, double categoryValue) {}
    record ChartResult(String id, List<ChartPoint> points) {}

    record Filters(List<UUID> projectIds, List<String> assignees, List<String> statuses,
            List<String> priorities, List<UUID> contentIds, List<String> categories, LocalDate dueFrom,
            LocalDate dueTo, boolean includeArchived, String query, boolean hasTime) {
        public static Filters empty() {
            return new Filters(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, false, "", false);
        }
    }
    record Bucket(String kind, String key, UUID projectId, UUID userId, String label,
            String code, String category, String colorToken, long count, long inProgress, long done, long durationMs) {}
    record Snapshot(List<Bucket> buckets, List<Bucket> options, Instant asOf) {}
    record Item(UUID id, UUID projectId, String itemNo, String title, UUID assigneeUserId, String assigneeName,
            String statusCode, String statusName, String statusCategory, String colorToken, long durationMs, Instant updatedAt,
            UUID contentId, String contentName, String priority, String priorityName, UUID reporterUserId, String reporterName,
            LocalDate dueDate, LocalDate timelineStartDate, LocalDate timelineEndDate, Instant createdAt, Instant completedAt,
            com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemListItem workItem) {}
    record Page(List<Item> items, long totalElements, Instant asOf) {}
}
