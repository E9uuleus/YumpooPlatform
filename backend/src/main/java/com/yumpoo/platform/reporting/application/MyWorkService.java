package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.TeamWorkQuery;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.yumpoo.platform.reporting.application.TeamDashboardModels.*;

/** The actor's own time and open work, limited to projects with an active membership; archived projects are excluded. */
@Service
public class MyWorkService {
    private static final int MAX_DAYS = 93, MAX_PROJECTS = 100;
    private final MemberProjectQuery projects;
    private final TeamWorkQuery work;
    private final MinimalUserSnapshotQuery users;
    private final CompanyConfigurationQuery company;
    private final Clock clock;
    public MyWorkService(MemberProjectQuery projects, TeamWorkQuery work, MinimalUserSnapshotQuery users,
            CompanyConfigurationQuery company, Clock clock) {
        this.projects = projects; this.work = work; this.users = users; this.company = company; this.clock = clock;
    }

    public Timesheet timesheet(CurrentActor actor, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw invalid("to", "统计区间需在 1–93 天之间");
        var scope = scope(actor);
        ZoneId zone = zone();
        Instant asOf = clock.instant();
        var entries = work.ownTimeEntries(actor, List.copyOf(scope.keySet()), from, to, zone, asOf);
        Map<UUID, TimesheetWorkItem> items = new LinkedHashMap<>();
        entries.forEach(e -> items.putIfAbsent(e.workItemId(),
                new TimesheetWorkItem(e.workItemId(), e.projectId(), scope.get(e.projectId()).name(), e.itemNo(), e.title())));
        var self = users.findByUserIds(actor.companyId(), List.of(actor.userId())).get(actor.userId());
        return new Timesheet(from, to, zone.getId(), asOf, List.of(new Member(actor.userId(), self.displayName(), self.activeAndEnabled())),
                List.copyOf(items.values()), entries.stream().map(e -> new TimesheetEntry(e.userId(), e.workItemId(), e.date(), e.durationMs())).toList());
    }

    public TaskPage currentTasks(CurrentActor actor, int offset, int limit) {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw invalid("limit", "分页范围无效");
        var scope = scope(actor);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone());
        var page = work.ownCurrentTasks(actor, List.copyOf(scope.keySet()), today, offset, limit);
        return new TaskPage(page.items().stream().map(t -> new Task(t.id(), t.projectId(), scope.get(t.projectId()).name(), t.itemNo(), t.title(),
                t.statusName(), t.statusCategory(), t.statusColor(), t.priorityName(), t.priorityColor(), t.dueDate(), t.overdue())).toList(),
                page.totalElements());
    }

    private Map<UUID, MemberProjectQuery.Project> scope(CurrentActor actor) {
        Map<UUID, MemberProjectQuery.Project> result = new LinkedHashMap<>();
        projects.search(actor, "", false, 0, MAX_PROJECTS).items().forEach(project -> result.put(project.id(), project));
        return result;
    }
    private ZoneId zone() { return company.current().timezone(); }
    private static ApplicationException invalid(String field, String message) {
        return ApplicationException.validation(new FieldViolation(field, "INVALID_VALUE", message));
    }
}
