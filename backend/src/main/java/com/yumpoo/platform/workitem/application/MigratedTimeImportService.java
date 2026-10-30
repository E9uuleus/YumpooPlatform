package com.yumpoo.platform.workitem.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.catalog.api.ProjectActiveMembershipQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.DayPlan;
import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.Existing;
import com.yumpoo.platform.workitem.application.MigratedTimeLayoutPlanner.Outcome;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

/** Administrator import of legacy daily hours per work item as read-only work item records, laid into each member's free time. */
@Service
public class MigratedTimeImportService {
    static final int MAX_ROWS = 20000;

    public record Row(UUID userId, LocalDate workDate, String projectName, String workItemTitle, Integer minutes) {}
    public record Request(Boolean dryRun, List<Row> rows) {}
    public record Issue(String code, String detail) {}
    public record Day(UUID userId, LocalDate workDate, int minutes, String outcome,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant startedAt, @JsonInclude(JsonInclude.Include.NON_NULL) Instant stoppedAt,
            int segmentCount, List<Issue> issues) {}
    /** {@code index} is the row's position in the request. */
    public record SkippedRow(int index, String code) {}
    public record Result(boolean dryRun, int importedDayCount, int failedDayCount, long importedMinutes, long replacedMinutes,
            long skippedMinutes, List<Day> days, List<SkippedRow> skippedRows) {}
    private record Target(UUID projectId, UUID workItemId) {}
    private record MemberDay(UUID userId, LocalDate workDate) {}

    private final TimeTrackingRepository timers;
    private final TimeShareMaintainer shares;
    private final CompanyProjectQuery projects;
    private final WorkItemRepository items;
    private final ProjectActiveMembershipQuery memberships;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public MigratedTimeImportService(TimeTrackingRepository timers, TimeShareMaintainer shares, CompanyProjectQuery projects, WorkItemRepository items,
            ProjectActiveMembershipQuery memberships, MinimalUserSnapshotQuery users, IdempotentCommandExecutor idempotency,
            SecurityAuditAppendPort audits, ObjectMapper json, Clock clock) {
        this.timers = timers; this.shares = shares; this.projects = projects; this.items = items; this.memberships = memberships;
        this.users = users; this.idempotency = idempotency; this.audits = audits; this.json = json; this.clock = clock;
    }

    public StoredCommandResult execute(CurrentActor actor, ZoneId zone, Request request, UUID key, RequestHash hash) {
        if (actor == null || !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(), "POST", "migratedTime:import", key), hash),
                () -> run(actor, zone, request)).result();
    }

    private StoredCommandResult run(CurrentActor actor, ZoneId zone, Request request) {
        validate(request);
        boolean dryRun = request.dryRun();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<Row> rows = request.rows();
        var skipped = new ArrayList<SkippedRow>();
        var targets = match(actor, rows, skipped);
        // Matched rows per member-day in file order; members in id order so concurrent imports and timer commands lock timer states in one order.
        var byUser = new TreeMap<UUID, LinkedHashMap<LocalDate, List<Integer>>>();
        for (int i = 0; i < rows.size(); i++)
            if (targets.containsKey(i)) byUser.computeIfAbsent(rows.get(i).userId(), id -> new LinkedHashMap<>())
                    .computeIfAbsent(rows.get(i).workDate(), date -> new ArrayList<>()).add(i);
        Map<UUID, MinimalUserSnapshot> members = users.findByUserIds(actor.companyId(), byUser.keySet());
        if (!dryRun) byUser.keySet().stream().filter(id -> active(members, id)).forEach(id -> timers.stateVersion(actor.companyId(), id, true));

        var plans = new HashMap<MemberDay, DayPlan>();
        var sessionsOf = new IdentityHashMap<Existing, Session>();
        for (var perUser : byUser.entrySet()) {
            UUID userId = perUser.getKey();
            if (!active(members, userId)) continue;
            var prefs = timers.preferences(actor.companyId(), userId).orElse(TimeTrackingPreferences.DEFAULT);
            LocalDate first = perUser.getValue().keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
            LocalDate last = perUser.getValue().keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
            var sessions = timers.mySessions(actor.companyId(), userId, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
                    .stream().map(MySessionRow::session).toList();
            // Earlier migrated records are replaced only for the work items this import writes on their day; legacy category records name no item and go with the day.
            var written = new HashMap<LocalDate, Set<UUID>>();
            perUser.getValue().forEach((date, indexes) -> written.put(date, indexes.stream().map(i -> targets.get(i).workItemId()).collect(Collectors.toSet())));
            var existing = new ArrayList<Existing>();
            for (Session session : sessions) {
                var writtenItems = written.get(LocalDate.ofInstant(session.startedAt(), zone));
                var item = new Existing(session.startedAt(), session.stoppedAt(), writtenItems != null && session.migratedImport()
                        && (session.workItemId() == null || writtenItems.contains(session.workItemId())));
                existing.add(item); sessionsOf.put(item, session);
            }
            for (var day : perUser.getValue().entrySet())
                plans.put(new MemberDay(userId, day.getKey()), MigratedTimeLayoutPlanner.plan(day.getKey(), zone, today, minutes(rows, day.getValue()),
                        prefs.workdayStartMinute(), prefs.workdayEndMinute(), existing));
        }

        var days = new ArrayList<Day>();
        int imported = 0, failed = 0; long importedMinutes = 0, replacedMinutes = 0;
        for (var perUser : byUser.entrySet()) for (var day : perUser.getValue().entrySet()) {
            int minutes = minutes(rows, day.getValue());
            DayPlan plan = plans.get(new MemberDay(perUser.getKey(), day.getKey()));
            if (plan == null) {
                failed++;
                days.add(new Day(perUser.getKey(), day.getKey(), minutes, Outcome.ERROR.name(), null, null, 0, List.of(new Issue("USER_INVALID", null))));
                continue;
            }
            boolean laid = plan.outcome() == Outcome.IMPORT;
            if (laid) { imported++; importedMinutes += minutes; replacedMinutes += plan.previousMinutes(); } else failed++;
            days.add(new Day(perUser.getKey(), day.getKey(), minutes, plan.outcome().name(),
                    laid ? plan.segments().getFirst().startedAt() : null, laid ? plan.segments().getLast().stoppedAt() : null,
                    plan.segments().size(), plan.issues().stream().map(i -> new Issue(i.code(), i.detail())).toList()));
        }
        long skippedMinutes = skipped.stream().mapToLong(row -> rows.get(row.index()).minutes()).sum();
        var result = new Result(dryRun, imported, failed, importedMinutes, replacedMinutes, skippedMinutes, days, List.copyOf(skipped));
        if (!dryRun) {
            write(actor, rows, targets, byUser, plans, sessionsOf, now);
            audit(actor, result, now);
        }
        return new StoredCommandResult(200, json.writeValueAsString(result), actor.userId(), null);
    }

    /** A row is written only when its project name, work item title and member match one ACTIVE project, one non-deleted item in it and an active membership. */
    private Map<Integer, Target> match(CurrentActor actor, List<Row> rows, List<SkippedRow> skipped) {
        var projectsByName = projects.listForAdministrator(actor).stream().filter(project -> "ACTIVE".equals(project.lifecycle()))
                .collect(Collectors.groupingBy(MemberProjectQuery.Project::name));
        var userIds = rows.stream().map(Row::userId).collect(Collectors.toSet());
        var titles = new HashMap<UUID, Map<String, List<UUID>>>();
        var memberIds = new HashMap<UUID, Set<UUID>>();
        var targets = new HashMap<Integer, Target>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            var named = projectsByName.getOrDefault(row.projectName().strip(), List.of());
            if (named.size() != 1) { skipped.add(new SkippedRow(i, named.isEmpty() ? "PROJECT_NOT_FOUND" : "PROJECT_AMBIGUOUS")); continue; }
            UUID projectId = named.getFirst().id();
            var found = titles.computeIfAbsent(projectId, id -> items.findTitles(actor.companyId(), id).stream()
                    .collect(Collectors.groupingBy(item -> item.title().strip(), Collectors.mapping(WorkItemRepository.WorkItemTitle::id, Collectors.toList()))))
                    .getOrDefault(row.workItemTitle().strip(), List.of());
            if (found.size() != 1) { skipped.add(new SkippedRow(i, found.isEmpty() ? "WORK_ITEM_NOT_FOUND" : "WORK_ITEM_AMBIGUOUS")); continue; }
            if (!memberIds.computeIfAbsent(projectId, id -> memberships.findActiveMemberIds(actor.companyId(), id, userIds)).contains(row.userId())) {
                skipped.add(new SkippedRow(i, "NOT_PROJECT_MEMBER")); continue;
            }
            targets.put(i, new Target(projectId, found.getFirst()));
        }
        return targets;
    }

    private void write(CurrentActor actor, List<Row> rows, Map<Integer, Target> targets, SortedMap<UUID, LinkedHashMap<LocalDate, List<Integer>>> byUser,
            Map<MemberDay, DayPlan> plans, Map<Existing, Session> sessionsOf, Instant now) {
        for (var perUser : byUser.entrySet()) {
            UUID userId = perUser.getKey();
            Instant from = null, to = null;
            for (var day : perUser.getValue().entrySet()) {
                DayPlan plan = plans.get(new MemberDay(userId, day.getKey()));
                if (plan == null || plan.outcome() != Outcome.IMPORT) continue;
                for (Existing old : plan.previous()) {
                    Session before = sessionsOf.get(old);
                    timers.save(before.next(before.startedAt(), before.stoppedAt(), now, null));
                    from = earliest(from, before.startedAt()); to = latest(to, before.stoppedAt());
                }
                var indexes = day.getValue();
                var pieces = MigratedTimeLayoutPlanner.split(plan.segments(), indexes.stream().map(i -> rows.get(i).minutes()).toList());
                for (int k = 0; k < indexes.size(); k++) {
                    Target target = targets.get(indexes.get(k));
                    for (var piece : pieces.get(k)) {
                        timers.save(Session.createMigrated(actor.companyId(), target.projectId(), target.workItemId(), userId, piece.startedAt(), piece.stoppedAt()));
                        from = earliest(from, piece.startedAt()); to = latest(to, piece.stoppedAt());
                    }
                }
            }
            if (from == null) continue;
            var affected = shares.rebuild(actor.companyId(), userId, from, to);
            if (!affected.isEmpty()) timers.advanceProjects(actor.companyId(), affected);
        }
    }

    private void audit(CurrentActor actor, Result result, Instant now) {
        UUID id = UUID.randomUUID();
        audits.append(new SecurityAuditDraft(actor.companyId(), "migrated-time-import:" + id, "MIGRATED_TIME_IMPORTED", SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), actor.platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())),
                "MIGRATED_TIME_IMPORT", id.toString(), null, null,
                json.valueToTree(Map.of("importedDayCount", result.importedDayCount(), "skippedRowCount", result.skippedRows().size(),
                        "failedDayCount", result.failedDayCount(), "importedMinutes", result.importedMinutes(),
                        "replacedMinutes", result.replacedMinutes())), null, null, null, null, now));
    }

    private static void validate(Request request) {
        if (request == null || request.dryRun() == null) throw validation("dryRun", "REQUIRED", "请指定是否仅预览");
        if (request.rows() == null || request.rows().isEmpty() || request.rows().size() > MAX_ROWS)
            throw validation("rows", "INVALID_SIZE", "一次须提交 1 到 20000 条记录");
        var perDay = new HashMap<List<Object>, Integer>();
        for (Row row : request.rows()) {
            if (row == null || row.userId() == null || row.workDate() == null || row.projectName() == null || row.workItemTitle() == null || row.minutes() == null)
                throw validation("rows", "REQUIRED", "成员、日期、项目、工作项和分钟数必填");
            if (row.minutes() < 1 || row.minutes() > 1440) throw validation("rows", "INVALID_RANGE", "分钟数须在 1 到 1440 之间");
            if (perDay.merge(List.of(row.userId(), row.workDate()), row.minutes(), Integer::sum) > 1440)
                throw validation("rows", "INVALID_RANGE", "同一成员同一天的合计不能超过 1440 分钟");
        }
    }

    private static int minutes(List<Row> rows, List<Integer> indexes) { return indexes.stream().mapToInt(i -> rows.get(i).minutes()).sum(); }
    private static boolean active(Map<UUID, MinimalUserSnapshot> members, UUID userId) {
        var member = members.get(userId);
        return member != null && member.activeAndEnabled();
    }
    private static Instant earliest(Instant a, Instant b) { return a == null || b.isBefore(a) ? b : a; }
    private static Instant latest(Instant a, Instant b) { return a == null || b.isAfter(a) ? b : a; }
    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
