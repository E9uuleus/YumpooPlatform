package com.yumpoo.platform.workitem.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectConnectionTargetQuery;
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
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

/** Administrator import of legacy per-day hours as read-only {@link TimeCategory#MIGRATED} sessions. */
@Service
public class MigratedTimeImportService {
    static final int MAX_ENTRIES = 20000;

    public record Entry(UUID userId, LocalDate workDate, Integer minutes) {}
    public record Request(Boolean dryRun, UUID categoryProjectId, List<Entry> entries) {}
    public record Issue(String code, String detail) {}
    public record Day(UUID userId, LocalDate workDate, int minutes, String outcome,
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant startedAt, @JsonInclude(JsonInclude.Include.NON_NULL) Instant stoppedAt,
            int segmentCount, List<Issue> issues) {}
    public record Result(boolean dryRun, int importedDayCount, int clearedDayCount, int failedDayCount,
            long importedMinutes, long replacedMinutes, List<Day> days) {}

    private final TimeTrackingRepository timers;
    private final TimeShareMaintainer shares;
    private final ProjectConnectionTargetQuery projectTargets;
    private final MinimalUserSnapshotQuery users;
    private final IdempotentCommandExecutor idempotency;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public MigratedTimeImportService(TimeTrackingRepository timers, TimeShareMaintainer shares, ProjectConnectionTargetQuery projectTargets,
            MinimalUserSnapshotQuery users, IdempotentCommandExecutor idempotency, SecurityAuditAppendPort audits, ObjectMapper json, Clock clock) {
        this.timers = timers; this.shares = shares; this.projectTargets = projectTargets; this.users = users;
        this.idempotency = idempotency; this.audits = audits; this.json = json; this.clock = clock;
    }

    public StoredCommandResult execute(CurrentActor actor, ZoneId zone, Request request, UUID key, RequestHash hash) {
        if (actor == null || !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(), "POST", "migratedTime:import", key), hash),
                () -> run(actor, zone, request)).result();
    }

    private StoredCommandResult run(CurrentActor actor, ZoneId zone, Request request) {
        validate(request);
        requireCategoryProject(actor.companyId(), request.categoryProjectId());
        boolean dryRun = request.dryRun();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDate today = LocalDate.ofInstant(now, zone);
        Map<UUID, MinimalUserSnapshot> members = users.findByUserIds(actor.companyId(),
                request.entries().stream().map(Entry::userId).collect(java.util.stream.Collectors.toSet()));
        // Users are processed in id order so concurrent imports and timer commands lock timer states in one order.
        var byUser = new TreeMap<UUID, List<Entry>>();
        for (Entry entry : request.entries()) {
            var member = members.get(entry.userId());
            if (member != null && member.activeAndEnabled()) byUser.computeIfAbsent(entry.userId(), id -> new ArrayList<>()).add(entry);
        }
        if (!dryRun) byUser.keySet().forEach(userId -> timers.stateVersion(actor.companyId(), userId, true));

        var plans = new HashMap<Entry, DayPlan>();
        var sessionsOf = new IdentityHashMap<Existing, Session>();
        for (var perUser : byUser.entrySet()) {
            UUID userId = perUser.getKey();
            var prefs = timers.preferences(actor.companyId(), userId).orElse(TimeTrackingPreferences.DEFAULT);
            LocalDate first = perUser.getValue().stream().map(Entry::workDate).min(Comparator.naturalOrder()).orElseThrow();
            LocalDate last = perUser.getValue().stream().map(Entry::workDate).max(Comparator.naturalOrder()).orElseThrow();
            var sessions = timers.mySessions(actor.companyId(), userId, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
                    .stream().map(MySessionRow::session).toList();
            var existing = new ArrayList<Existing>();
            for (Session session : sessions) {
                var item = new Existing(session.startedAt(), session.stoppedAt(), session.timeCategory() == TimeCategory.MIGRATED);
                existing.add(item); sessionsOf.put(item, session);
            }
            for (Entry entry : perUser.getValue())
                plans.put(entry, MigratedTimeLayoutPlanner.plan(entry.workDate(), zone, today, entry.minutes(),
                        prefs.workdayStartMinute(), prefs.workdayEndMinute(), existing));
        }

        var days = new ArrayList<Day>();
        int imported = 0, cleared = 0, failed = 0; long importedMinutes = 0, replacedMinutes = 0;
        for (Entry entry : request.entries()) {
            DayPlan plan = plans.get(entry);
            if (plan == null) {
                failed++;
                days.add(new Day(entry.userId(), entry.workDate(), entry.minutes(), Outcome.ERROR.name(), null, null, 0, List.of(new Issue("USER_INVALID", null))));
                continue;
            }
            switch (plan.outcome()) {
                case IMPORT -> { imported++; importedMinutes += entry.minutes(); replacedMinutes += plan.previousMinutes(); }
                case CLEAR -> { cleared++; replacedMinutes += plan.previousMinutes(); }
                case ERROR -> failed++;
            }
            boolean laid = plan.outcome() == Outcome.IMPORT;
            days.add(new Day(entry.userId(), entry.workDate(), entry.minutes(), plan.outcome().name(),
                    laid ? plan.segments().getFirst().startedAt() : null, laid ? plan.segments().getLast().stoppedAt() : null,
                    plan.segments().size(), plan.issues().stream().map(i -> new Issue(i.code(), i.detail())).toList()));
        }
        var result = new Result(dryRun, imported, cleared, failed, importedMinutes, replacedMinutes, days);
        if (!dryRun) {
            write(actor, request.categoryProjectId(), byUser, plans, sessionsOf, now);
            audit(actor, result, now);
        }
        return new StoredCommandResult(200, json.writeValueAsString(result), actor.userId(), null);
    }

    private void write(CurrentActor actor, UUID categoryProjectId, SortedMap<UUID, List<Entry>> byUser, Map<Entry, DayPlan> plans,
            Map<Existing, Session> sessionsOf, Instant now) {
        for (var perUser : byUser.entrySet()) {
            UUID userId = perUser.getKey();
            Instant from = null, to = null;
            for (Entry entry : perUser.getValue()) {
                DayPlan plan = plans.get(entry);
                if (plan.outcome() == Outcome.ERROR) continue;
                for (Existing old : plan.previous()) {
                    Session before = sessionsOf.get(old);
                    timers.save(before.next(before.startedAt(), before.stoppedAt(), now, null));
                    from = earliest(from, before.startedAt()); to = latest(to, before.stoppedAt());
                }
                for (var segment : plan.segments()) {
                    timers.save(Session.create(actor.companyId(), null, TimeSubject.of(null, TimeCategory.MIGRATED, categoryProjectId), userId,
                            segment.startedAt(), segment.stoppedAt(), "MANUAL"));
                    from = earliest(from, segment.startedAt()); to = latest(to, segment.stoppedAt());
                }
            }
            if (from == null) continue;
            var affected = shares.rebuild(actor.companyId(), userId, from, to);
            if (!affected.isEmpty()) timers.touchProjects(actor.companyId(), affected);
        }
    }

    private void audit(CurrentActor actor, Result result, Instant now) {
        UUID id = UUID.randomUUID();
        audits.append(new SecurityAuditDraft(actor.companyId(), "migrated-time-import:" + id, "MIGRATED_TIME_IMPORTED", SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), actor.platformRoles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())),
                "MIGRATED_TIME_IMPORT", id.toString(), null, null,
                json.valueToTree(Map.of("importedDayCount", result.importedDayCount(), "clearedDayCount", result.clearedDayCount(),
                        "failedDayCount", result.failedDayCount(), "importedMinutes", result.importedMinutes(),
                        "replacedMinutes", result.replacedMinutes())), null, null, null, null, now));
    }

    private void requireCategoryProject(UUID companyId, UUID projectId) {
        if (projectId == null) return;
        var project = projectTargets.findByIds(companyId, Set.of(projectId)).get(projectId);
        if (project == null || project.lifecycle() != ProjectAccessSnapshot.ProjectLifecycle.ACTIVE || project.purging())
            throw validation("categoryProjectId", "PROJECT_UNAVAILABLE", "无法关联该项目");
    }

    private static void validate(Request request) {
        if (request == null || request.dryRun() == null) throw validation("dryRun", "REQUIRED", "请指定是否仅预览");
        if (request.entries() == null || request.entries().isEmpty() || request.entries().size() > MAX_ENTRIES)
            throw validation("entries", "INVALID_SIZE", "一次须提交 1 到 20000 条记录");
        var seen = new HashSet<List<Object>>();
        for (Entry entry : request.entries()) {
            if (entry == null || entry.userId() == null || entry.workDate() == null || entry.minutes() == null)
                throw validation("entries", "REQUIRED", "成员、日期和分钟数必填");
            if (entry.minutes() < 0 || entry.minutes() > 1440) throw validation("entries", "INVALID_RANGE", "分钟数须在 0 到 1440 之间");
            if (!seen.add(List.of(entry.userId(), entry.workDate()))) throw validation("entries", "DUPLICATE_ENTRY", "同一成员同一天不能重复");
        }
    }

    private static Instant earliest(Instant a, Instant b) { return a == null || b.isBefore(a) ? b : a; }
    private static Instant latest(Instant a, Instant b) { return a == null || b.isAfter(a) ? b : a; }
    private static ApplicationException validation(String field, String code, String message) {
        return ApplicationException.validation(new FieldViolation(field, code, message));
    }
}
