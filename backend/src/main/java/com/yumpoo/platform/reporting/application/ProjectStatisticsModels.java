package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ProjectStatisticsModels {
    private ProjectStatisticsModels() {}
    public record StatisticsQuery(WorkItemStatisticsQuery.Filters filters, List<DashboardModels.Widget> widgets) {}
    public record StatisticsResponse(Instant asOf, List<WorkItemStatisticsQuery.ChartResult> charts) {}
    public record WorklogMember(UUID userId, String displayName, boolean projectMember) {}
    public record WorklogEntry(UUID userId, UUID workItemId, String itemNo, String title, long durationMs) {}
    public record Worklog(LocalDate from, LocalDate to, Instant asOf, List<WorklogMember> members, List<WorklogEntry> entries) {}
}
