package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.TeamWorkQuery;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.yumpoo.platform.reporting.application.TeamDashboardModels.*;

/** Company-admin team view; the catalog and workitem ports reject non-administrators before any data is read. */
@Service
public class TeamDashboardService {
    private static final int MAX_DAYS = 93, MAX_PROJECTS = 500, MAX_USERS = 1000, MEMBER_PAGE = 100, MAX_MEMBER_PAGES = 50;
    private final CompanyProjectQuery projects;
    private final TeamWorkQuery work;
    private final MinimalUserSnapshotQuery users;
    private final CompanyConfigurationQuery company;
    private final Clock clock;
    public TeamDashboardService(CompanyProjectQuery projects, TeamWorkQuery work, MinimalUserSnapshotQuery users,
            CompanyConfigurationQuery company, Clock clock) {
        this.projects = projects; this.work = work; this.users = users; this.company = company; this.clock = clock;
    }

    public Options options(CurrentActor actor) {
        var all = projects.listForAdministrator(actor).stream().map(p -> new Project(p.id(), p.name(), p.code(), p.lifecycle())).toList();
        return new Options(all, activeMembers(actor.companyId()), zone().getId());
    }

    public Timesheet timesheet(CurrentActor actor, TimesheetQuery query) {
        var scope = scope(actor, ids(query == null ? null : query.projectIds(), MAX_PROJECTS, "projectIds"), true);
        if (query == null || query.from() == null || query.to() == null || query.to().isBefore(query.from())
                || ChronoUnit.DAYS.between(query.from(), query.to()) >= MAX_DAYS)
            throw invalid("to", "统计区间需在 1–93 天之间");
        var userIds = ids(query.userIds(), MAX_USERS, "userIds");
        ZoneId zone = zone();
        Instant asOf = clock.instant();
        var entries = work.timeEntries(actor, List.copyOf(scope.keySet()), userIds, query.from(), query.to(), zone, asOf);
        Map<UUID, TimesheetWorkItem> items = new LinkedHashMap<>();
        entries.forEach(e -> items.putIfAbsent(e.workItemId(),
                new TimesheetWorkItem(e.workItemId(), e.projectId(), scope.get(e.projectId()).name(), e.itemNo(), e.title())));
        var withTime = entries.stream().map(TeamWorkQuery.TimeEntry::userId).collect(Collectors.toCollection(LinkedHashSet::new));
        return new Timesheet(query.from(), query.to(), zone.getId(), asOf, members(actor.companyId(), userIds, withTime),
                List.copyOf(items.values()), entries.stream().map(e -> new TimesheetEntry(e.userId(), e.workItemId(), e.date(), e.durationMs())).toList());
    }

    public Workload workload(CurrentActor actor, WorkloadQuery query) {
        var scope = scope(actor, ids(query == null ? null : query.projectIds(), MAX_PROJECTS, "projectIds"), false);
        var userIds = ids(query == null ? null : query.userIds(), MAX_USERS, "userIds");
        Instant asOf = clock.instant();
        LocalDate today = LocalDate.ofInstant(asOf, zone());
        Map<UUID, TeamWorkQuery.Load> loads = new HashMap<>();
        var unassigned = new WorkloadCounts(0, 0, 0);
        for (var load : work.currentLoad(actor, List.copyOf(scope.keySet()), today)) {
            if (load.userId() != null) loads.put(load.userId(), load);
            else if (userIds.isEmpty()) unassigned = new WorkloadCounts(load.todo(), load.inProgress(), load.overdue());
        }
        var rows = members(actor.companyId(), userIds, userIds.isEmpty() ? loads.keySet() : Set.of()).stream().map(m -> {
            var load = loads.get(m.userId());
            return load == null ? new WorkloadMember(m.userId(), m.displayName(), m.active(), 0, 0, 0)
                    : new WorkloadMember(m.userId(), m.displayName(), m.active(), load.todo(), load.inProgress(), load.overdue());
        }).toList();
        return new Workload(asOf, today, rows, unassigned);
    }

    public TaskPage tasks(CurrentActor actor, TasksQuery query) {
        if (query == null || query.offset() == null || query.limit() == null || query.offset() < 0 || query.offset() > 100_000
                || query.limit() < 1 || query.limit() > 100)
            throw invalid("limit", "分页范围无效");
        var scope = scope(actor, ids(query.projectIds(), MAX_PROJECTS, "projectIds"), false);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone());
        var page = work.currentTasks(actor, List.copyOf(scope.keySet()), query.userId(), today, query.offset(), query.limit());
        return new TaskPage(page.items().stream().map(t -> new Task(t.id(), t.projectId(), scope.get(t.projectId()).name(), t.itemNo(), t.title(),
                t.statusName(), t.statusCategory(), t.statusColor(), t.priorityName(), t.priorityColor(), t.dueDate(), t.overdue())).toList(),
                page.totalElements());
    }

    /** An empty request means every project (archived ones only for time history); explicit ids are intersected with the company. */
    private Map<UUID, MemberProjectQuery.Project> scope(CurrentActor actor, List<UUID> requested, boolean includeArchived) {
        Map<UUID, MemberProjectQuery.Project> result = new LinkedHashMap<>();
        for (var project : projects.listForAdministrator(actor)) {
            if (requested.isEmpty() ? includeArchived || !"ARCHIVED".equals(project.lifecycle()) : requested.contains(project.id()))
                result.put(project.id(), project);
        }
        return result;
    }

    /** Active members when no member filter is given, plus anyone with data; unknown accounts show as 历史成员. */
    private List<Member> members(UUID companyId, List<UUID> requested, Set<UUID> withData) {
        Map<UUID, Member> rows = new LinkedHashMap<>();
        if (requested.isEmpty()) activeMembers(companyId).forEach(member -> rows.put(member.userId(), member));
        var missing = Stream.concat(requested.stream(), withData.stream()).filter(id -> !rows.containsKey(id)).distinct().toList();
        Map<UUID, MinimalUserSnapshot> found = missing.isEmpty() ? Map.of() : users.findByUserIds(companyId, missing);
        for (UUID id : missing) {
            var user = found.get(id);
            rows.put(id, user == null ? new Member(id, "历史成员", false) : new Member(id, user.displayName(), user.activeAndEnabled()));
        }
        return List.copyOf(rows.values());
    }

    private List<Member> activeMembers(UUID companyId) {
        List<Member> result = new ArrayList<>();
        for (int page = 0; page < MAX_MEMBER_PAGES; page++) {
            var items = users.findActiveEnabledByName(companyId, "", OffsetPageRequest.of(page, MEMBER_PAGE)).items();
            items.forEach(user -> result.add(new Member(user.userId(), user.displayName(), true)));
            if (items.size() < MEMBER_PAGE) break;
        }
        return result;
    }

    private static List<UUID> ids(List<UUID> values, int max, String field) {
        if (values == null) return List.of();
        if (values.size() > max || values.stream().anyMatch(Objects::isNull)) throw invalid(field, "筛选条件过多或无效");
        return values.stream().distinct().toList();
    }
    private ZoneId zone() { return company.current().timezone(); }
    private static ApplicationException invalid(String field, String message) {
        return ApplicationException.validation(new FieldViolation(field, "INVALID_VALUE", message));
    }
}
