package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.*;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import static com.yumpoo.platform.reporting.application.DashboardModels.*;

@Service
public class DashboardService {
    private final DashboardRepository repository;
    private final MemberProjectQuery projects;
    private final WorkItemStatisticsQuery statistics;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;
    private final Clock clock;
    public DashboardService(DashboardRepository repository, MemberProjectQuery projects,
            WorkItemStatisticsQuery statistics, IdempotentCommandExecutor idempotency, ObjectMapper json, Clock clock) {
        this.repository = repository; this.projects = projects; this.statistics = statistics;
        this.idempotency = idempotency; this.json = json; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public ListResponse list(CurrentActor actor) {
        return new ListResponse(repository.list(actor.companyId(), actor.userId()).stream().map(d ->
                new Summary(d.id(), d.name(), d.configuration().projectIds().size(), d.configuration().widgets().size(), d.updatedAt())).toList());
    }
    @Transactional(readOnly = true)
    public Stored requireOwned(CurrentActor actor, UUID id, boolean includeDeleted) {
        return repository.find(actor.companyId(), actor.userId(), id, includeDeleted)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }
    @Transactional(readOnly = true)
    public View get(CurrentActor actor, UUID id) { return view(actor, requireOwned(actor, id, false)); }

    @Transactional
    public IdempotencyExecutionResult command(CurrentActor actor, String method, UUID id, Write input,
            long expected, UUID key, RequestHash hash) {
        if (id != null) requireOwned(actor, id, method.equals("DELETE"));
        return idempotency.execute(new IdempotencyCommand(new IdempotencyScope(actor.userId(), method,
                "/me/dashboards" + (id == null ? "" : "/" + id), key), hash), () -> {
            Stored before = id == null ? null : requireOwned(actor, id, false);
            if (before != null && before.version() != expected) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            var now = clock.instant();
            if (method.equals("DELETE")) {
                if (!repository.delete(actor.companyId(), actor.userId(), id, expected, now))
                    throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
                return new StoredCommandResult(200, "{\"deleted\":true}", id, null);
            }
            Write valid = DashboardValidation.normalize(input, statistics);
            List<UUID> added = valid.configuration().projectIds().stream()
                    .filter(project -> before == null || !before.configuration().projectIds().contains(project)).toList();
            if (projects.find(actor, added).size() != added.size())
                throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
            Stored next = new Stored(id == null ? UUID.randomUUID() : id, actor.companyId(), actor.userId(), valid.name(),
                    valid.configuration(), before == null ? 0 : expected + 1, before == null ? now : before.createdAt(), now, null);
            if (before == null) repository.insert(next);
            else if (!repository.update(next, expected)) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
            return new StoredCommandResult(before == null ? 201 : 200, json.writeValueAsString(view(actor, next)), next.id(), StrongEtag.format(next.version()));
        });
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QueryResponse query(CurrentActor actor, UUID id, Query query) {
        var dashboard = requireOwned(actor, id, false);
        var result = statistics.aggregate(actor, dashboard.configuration().projectIds(),
                query == null || query.filters() == null ? dashboard.configuration().filters() : query.filters());
        return new QueryResponse(result.buckets(), result.options(), result.asOf(), view(actor, dashboard).projects());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkItemStatisticsQuery.Page items(CurrentActor actor, UUID id, ItemsQuery query) {
        var dashboard = requireOwned(actor, id, false);
        return statistics.items(actor, dashboard.configuration().projectIds(),
                query.filters() == null ? dashboard.configuration().filters() : query.filters(), query.offset(), query.limit());
    }
    private View view(CurrentActor actor, Stored d) {
        Map<UUID, MemberProjectQuery.Project> visible = projects.find(actor, d.configuration().projectIds()).stream()
                .collect(Collectors.toMap(MemberProjectQuery.Project::id, p -> p));
        var connections = d.configuration().projectIds().stream().map(id -> {
            var p = visible.get(id);
            return p == null ? new Connection(id, null, null, null, false) : new Connection(id, p.name(), p.code(), p.lifecycle(), true);
        }).toList();
        return new View(d.id(), d.name(), d.configuration(), connections, d.version(), StrongEtag.format(d.version()), d.updatedAt());
    }
}
