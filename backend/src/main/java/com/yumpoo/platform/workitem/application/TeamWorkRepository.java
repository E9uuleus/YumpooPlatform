package com.yumpoo.platform.workitem.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface TeamWorkRepository {
    record TimeEntry(UUID userId, UUID workItemId, UUID projectId, String itemNo, String title, LocalDate date, long durationMs) {}
    record Load(UUID userId, long todo, long inProgress, long overdue) {}
    record Task(UUID id, UUID projectId, String itemNo, String title, String statusName, String statusCategory, String statusColor,
            String priorityName, String priorityColor, LocalDate dueDate, boolean overdue) {}

    List<TimeEntry> timeEntries(UUID companyId, List<UUID> projectIds, List<UUID> userIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf);
    List<Load> currentLoad(UUID companyId, List<UUID> projectIds, LocalDate today);
    List<Task> currentTasks(UUID companyId, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit);
    long countCurrentTasks(UUID companyId, List<UUID> projectIds, UUID userId);
}
