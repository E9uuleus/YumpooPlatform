package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.organization.api.CalendarDayType;
import com.yumpoo.platform.organization.api.CompanyCalendarDaySnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TeamDashboardModels {
    private TeamDashboardModels() {}
    public record Project(UUID id, String name, String code, String lifecycle) {}
    public record Member(UUID userId, String displayName, boolean active) {}
    public record Options(List<Project> projects, List<Member> members, String timezone) {}
    public record TimesheetQuery(LocalDate from, LocalDate to, List<UUID> projectIds, List<UUID> userIds) {}
    public record TimesheetWorkItem(UUID id, UUID projectId, String projectName, String itemNo, String title) {}
    public record TimesheetEntry(UUID userId, UUID workItemId, LocalDate date, long durationMs, long migratedDurationMs) {}
    /** Category time; projectId/projectName are set only when the linked project is in the caller's scope. */
    public record TimesheetCategoryEntry(UUID userId, String timeCategory, UUID projectId, String projectName, LocalDate date, long durationMs) {}
    public record CalendarDay(LocalDate date, CalendarDayType dayType, String note, int standardMinutes) {
        public static List<CalendarDay> of(List<CompanyCalendarDaySnapshot> days) {
            return days.stream().map(d -> new CalendarDay(d.date(), d.dayType(), d.note(), d.standardMinutes())).toList();
        }
    }
    public record Timesheet(LocalDate from, LocalDate to, String timezone, Instant asOf, List<Member> members,
            List<TimesheetWorkItem> workItems, List<TimesheetEntry> entries, List<TimesheetCategoryEntry> categoryEntries,
            List<CalendarDay> days) {
        static List<TimesheetCategoryEntry> categories(List<com.yumpoo.platform.workitem.api.TeamWorkQuery.CategoryEntry> entries, Map<UUID,String> projectNames) {
            return entries.stream().map(e -> {
                String name=e.projectId()==null ? null : projectNames.get(e.projectId());
                return new TimesheetCategoryEntry(e.userId(),e.timeCategory(),name==null ? null : e.projectId(),name,e.date(),e.durationMs());
            }).toList();
        }
    }
    public record WorkloadQuery(List<UUID> projectIds, List<UUID> userIds) {}
    public record WorkloadCounts(long todo, long inProgress, long other, long overdue) {}
    public record WorkloadMember(UUID userId, String displayName, boolean active, long todo, long inProgress, long other, long overdue) {}
    public record Workload(Instant asOf, LocalDate today, List<WorkloadMember> members, WorkloadCounts unassigned) {}
    public record TasksQuery(UUID userId, List<UUID> projectIds, Integer offset, Integer limit) {}
    public record Task(UUID id, UUID projectId, String projectName, String itemNo, String title, String statusCode, String statusName, String statusCategory,
            String statusColor, String priorityName, String priorityColor, LocalDate dueDate, boolean overdue) {}
    public record TaskPage(List<Task> items, long totalElements) {}
}
