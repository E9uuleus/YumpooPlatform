package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.StatisticsFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkItemStatisticsRepository {
    record Bucket(String kind, String key, UUID projectId, UUID userId, String label,
            String code, String category, String colorToken, long count, long inProgress, long done, long durationMs) {
        public Bucket named(String name) {
            return new Bucket(kind, key, projectId, userId, name, code, category, colorToken, count, inProgress, done, durationMs);
        }
    }
    record Item(UUID id, UUID projectId, String itemNo, String title, UUID assigneeUserId,
            String statusCode, String statusName, String statusCategory, String colorToken,
            long durationMs, Instant updatedAt, UUID contentId, String contentName, String priority,
            String priorityName, UUID reporterUserId, java.time.LocalDate dueDate, java.time.LocalDate timelineStartDate,
            java.time.LocalDate timelineEndDate, Instant createdAt, Instant completedAt) {}
    List<Bucket> aggregate(UUID companyId, StatisticsFilter filter, Instant asOf, boolean options);
    List<Item> items(UUID companyId, StatisticsFilter filter, Instant asOf, int offset, int limit);
    long count(UUID companyId, StatisticsFilter filter, Instant asOf);
}
