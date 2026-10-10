package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Administrator reporting across the company; rejects actors without COMPANY_ADMIN. The own* variants read only the
 * actor's own sessions and assignments and need no administrator role. Project ids must come from the caller's scope.
 * Time is split per company-calendar day; open work counts each assignee once.
 */
public interface TeamWorkQuery {
    List<TimeEntry> timeEntries(CurrentActor actor, List<UUID> projectIds, List<UUID> userIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf);
    List<Load> currentLoad(CurrentActor actor, List<UUID> projectIds, LocalDate today);
    TaskPage currentTasks(CurrentActor actor, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit);
    List<TimeEntry> ownTimeEntries(CurrentActor actor, List<UUID> projectIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf);
    TaskPage ownCurrentTasks(CurrentActor actor, List<UUID> projectIds, LocalDate today, int offset, int limit);

    record TimeEntry(UUID userId, UUID workItemId, UUID projectId, String itemNo, String title, LocalDate date, long durationMs) {}
    /** A null user groups open work without assignees. */
    record Load(UUID userId, long todo, long inProgress, long overdue) {}
    record Task(UUID id, UUID projectId, String itemNo, String title, String statusName, String statusCategory, String statusColor,
            String priorityName, String priorityColor, LocalDate dueDate, boolean overdue) {}
    record TaskPage(List<Task> items, long totalElements) {}
}
