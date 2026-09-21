package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.domain.StatisticsFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.yumpoo.platform.workitem.application.WorkItemStatisticsRepository.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WorkItemStatisticsService {
    private final WorkItemStatisticsRepository repository;
    private final MemberProjectQuery projects;
    private final MinimalUserSnapshotQuery users;
    private final Clock clock;
    public WorkItemStatisticsService(WorkItemStatisticsRepository repository, MemberProjectQuery projects,
            MinimalUserSnapshotQuery users, Clock clock) {
        this.repository = repository; this.projects = projects; this.users = users; this.clock = clock;
    }
    public record Snapshot(List<Bucket> buckets, List<Bucket> options, Instant asOf) {}
    public record ItemRow(Item item, String assigneeName, String reporterName) {}
    public record Page(List<ItemRow> items, long totalElements, Instant asOf) {}
    public record Request(List<UUID> projectIds, List<String> assignees, List<String> statuses,
            List<String> priorities, List<UUID> contentIds, List<String> categories, java.time.LocalDate dueFrom,
            java.time.LocalDate dueTo, boolean includeArchived, String query, boolean hasTime) {
        StatisticsFilter filter() {
            return new StatisticsFilter(projectIds, assignees, statuses, priorities, contentIds, categories,
                    dueFrom, dueTo, includeArchived, query, hasTime);
        }
    }
    public void validate(Request request) { request.filter(); }

    public Snapshot aggregate(CurrentActor actor, List<UUID> connected, Request request) {
        StatisticsFilter requested = request.filter();
        Instant now = clock.instant();
        List<UUID> allowed = projects.find(actor, connected).stream().map(MemberProjectQuery.Project::id).toList();
        var filter = requested.within(allowed.stream()
                .filter(id -> requested.projectIds().isEmpty() || requested.projectIds().contains(id)).toList());
        var buckets = filter.projectIds().isEmpty() ? List.<Bucket>of() : repository.aggregate(actor.companyId(), filter, now, false);
        var options = allowed.isEmpty() ? List.<Bucket>of()
                : repository.aggregate(actor.companyId(), requested.options().within(allowed), now, true);
        var ids = java.util.stream.Stream.concat(buckets.stream(), options.stream()).map(Bucket::userId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        var people = users.findByUserIds(actor.companyId(), ids);
        return new Snapshot(names(buckets, people), names(options, people), now);
    }
    public Page items(CurrentActor actor, List<UUID> connected, Request request, int offset, int limit) {
        StatisticsFilter requested = request.filter();
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100)
            throw ApplicationException.validation(new FieldViolation("offset", "INVALID_VALUE", "分页范围无效"));
        Instant now = clock.instant();
        var allowed = projects.find(actor, connected).stream().map(MemberProjectQuery.Project::id)
                .filter(id -> requested.projectIds().isEmpty() || requested.projectIds().contains(id)).toList();
        if (allowed.isEmpty()) return new Page(List.of(), 0, now);
        var filter = requested.within(allowed);
        var rows = repository.items(actor.companyId(), filter, now, offset, limit);
        var people = users.findByUserIds(actor.companyId(), rows.stream().flatMap(row -> java.util.stream.Stream.of(row.assigneeUserId(), row.reporterUserId()))
                .filter(java.util.Objects::nonNull).distinct().toList());
        return new Page(rows.stream().map(row -> new ItemRow(row, name(row.assigneeUserId(), people), name(row.reporterUserId(), people))).toList(),
                repository.count(actor.companyId(), filter, now), now);
    }
    private static List<Bucket> names(List<Bucket> rows, Map<UUID, MinimalUserSnapshot> people) {
        return rows.stream().map(row -> row.kind().equals("MEMBER") ? row.named(name(row.userId(), people)) : row).toList();
    }
    private static String name(UUID id, Map<UUID, MinimalUserSnapshot> people) {
        if (id == null) return "未分配";
        var user = people.get(id);
        return user == null ? "历史成员" : user.displayName();
    }
}
