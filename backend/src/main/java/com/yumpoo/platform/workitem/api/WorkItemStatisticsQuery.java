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
            String statusCode, String statusName, String statusCategory, String colorToken, long durationMs, Instant updatedAt) {}
    record Page(List<Item> items, long totalElements, Instant asOf) {}
}
