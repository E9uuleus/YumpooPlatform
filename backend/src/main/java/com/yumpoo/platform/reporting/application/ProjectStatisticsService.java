package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.reporting.application.ProjectStatisticsModels.*;
import com.yumpoo.platform.workitem.api.TeamWorkQuery;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ProjectStatisticsService {
    private static final int MAX_DAYS = 93;
    private final WorkItemStatisticsQuery statistics;
    private final TeamWorkQuery work;
    private final ProjectActiveMembershipQuery memberships;
    private final MinimalUserSnapshotQuery users;
    private final CompanyConfigurationQuery company;
    private final Clock clock;
    public ProjectStatisticsService(WorkItemStatisticsQuery statistics, TeamWorkQuery work, ProjectActiveMembershipQuery memberships,
            MinimalUserSnapshotQuery users, CompanyConfigurationQuery company, Clock clock) {
        this.statistics = statistics; this.work = work; this.memberships = memberships;
        this.users = users; this.company = company; this.clock = clock;
    }

    public StatisticsResponse query(CurrentActor actor, UUID projectId, StatisticsQuery query) {
        if (query.widgets() == null) throw ApplicationException.validation(new FieldViolation("widgets", "REQUIRED", "图表配置不能为空"));
        DashboardService.validateQueryWidgets(query.widgets(), statistics);
        Instant asOf = clock.instant();
        return new StatisticsResponse(asOf, statistics.projectCharts(actor, projectId, query.filters(),
                query.widgets().stream().map(DashboardCharts::request).toList(), asOf));
    }

    public WorkItemStatisticsQuery.Page items(CurrentActor actor, UUID projectId, DashboardModels.ItemsQuery query) {
        var widget = widget(query.widget());
        return statistics.projectChartItems(actor, projectId, query.filters(), DashboardCharts.request(widget),
                query.selection(), query.offset(), query.limit());
    }

    /** The path project wins; the body projectId only exists because the shared table request requires it. */
    public WorkItemStatisticsQuery.TablePage table(CurrentActor actor, UUID projectId, DashboardModels.TableQuery query) {
        var widget = widget(query.widget());
        return statistics.projectTable(actor, projectId, query.filters(), DashboardCharts.request(widget), query.selection(), query.table());
    }

    public Worklog worklog(CurrentActor actor, UUID projectId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)
            throw ApplicationException.validation(new FieldViolation("to", "INVALID_VALUE", "统计区间需在 1–93 天之间"));
        Instant asOf = clock.instant();
        Map<List<UUID>, WorklogEntry> totals = new LinkedHashMap<>();
        for (var e : work.projectTimeEntries(actor, projectId, from, to, company.current().timezone(), asOf))
            totals.merge(List.of(e.userId(), e.workItemId()), new WorklogEntry(e.userId(), e.workItemId(), e.itemNo(), e.title(), e.durationMs()),
                    (a, b) -> new WorklogEntry(a.userId(), a.workItemId(), a.itemNo(), a.title(), a.durationMs() + b.durationMs()));
        var userIds = totals.values().stream().map(WorklogEntry::userId).distinct().toList();
        Map<UUID, MinimalUserSnapshot> found = userIds.isEmpty() ? Map.of() : users.findByUserIds(actor.companyId(), userIds);
        Set<UUID> members = userIds.isEmpty() ? Set.of() : memberships.findActiveMemberIds(actor.companyId(), projectId, userIds);
        return new Worklog(from, to, asOf,
                userIds.stream().map(id -> new WorklogMember(id, found.containsKey(id) ? found.get(id).displayName() : "历史成员", members.contains(id))).toList(),
                List.copyOf(totals.values()));
    }

    private static DashboardModels.Widget widget(DashboardModels.Widget widget) {
        if (widget == null) throw ApplicationException.validation(new FieldViolation("widget", "REQUIRED", "图表不能为空"));
        return widget;
    }
}
