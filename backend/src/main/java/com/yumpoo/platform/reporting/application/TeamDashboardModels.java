package com.yumpoo.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TeamDashboardModels {
    private TeamDashboardModels() {}
    public record Project(UUID id, String name, String code, String lifecycle) {}
    public record Member(UUID userId, String displayName, boolean active) {}
    public record Options(List<Project> projects, List<Member> members, String timezone) {}
    public record TimesheetQuery(LocalDate from, LocalDate to, List<UUID> projectIds, List<UUID> userIds) {}
    public record TimesheetWorkItem(UUID id, UUID projectId, String projectName, String itemNo, String title) {}
    public record TimesheetEntry(UUID userId, UUID workItemId, LocalDate date, long durationMs) {}
    public record Timesheet(LocalDate from, LocalDate to, String timezone, Instant asOf, List<Member> members,
            List<TimesheetWorkItem> workItems, List<TimesheetEntry> entries) {}
    public record WorkloadQuery(List<UUID> projectIds, List<UUID> userIds) {}
    public record WorkloadCounts(long todo, long inProgress, long overdue) {}
    public record WorkloadMember(UUID userId, String displayName, boolean active, long todo, long inProgress, long overdue) {}
    public record Workload(Instant asOf, LocalDate today, List<WorkloadMember> members, WorkloadCounts unassigned) {}
    public record TasksQuery(UUID userId, List<UUID> projectIds, Integer offset, Integer limit) {}
    public record Task(UUID id, UUID projectId, String projectName, String itemNo, String title, String statusName, String statusCategory,
            String statusColor, String priorityName, String priorityColor, LocalDate dueDate, boolean overdue) {}
    public record TaskPage(List<Task> items, long totalElements) {}
}
