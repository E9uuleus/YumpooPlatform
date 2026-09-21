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
        var widgets = query == null || query.widgets() == null ? dashboard.configuration().widgets() : query.widgets();
        validateQueryWidgets(widgets);
        var charts = statistics.charts(actor, dashboard.configuration().projectIds(),
                query == null || query.filters() == null ? dashboard.configuration().filters() : query.filters(),
                widgets.stream().map(DashboardCharts::request).toList(), result.asOf());
        return new QueryResponse(result.buckets(), result.options(), result.asOf(), view(actor, dashboard).projects(), charts);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkItemStatisticsQuery.Page items(CurrentActor actor, UUID id, ItemsQuery query) {
        var dashboard = requireOwned(actor, id, false);
        if (query.widget() != null) {
            validateQueryWidgets(List.of(query.widget()));
            return statistics.chartItems(actor, dashboard.configuration().projectIds(),
                    query.filters() == null ? dashboard.configuration().filters() : query.filters(),
                    DashboardCharts.request(query.widget()), query.selection(), query.offset(), query.limit());
        }
        return statistics.items(actor, dashboard.configuration().projectIds(),
                query.filters() == null ? dashboard.configuration().filters() : query.filters(), query.offset(), query.limit());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkItemStatisticsQuery.TablePage table(CurrentActor actor, UUID id, TableQuery query) {
        var dashboard = requireOwned(actor, id, false);
        if (query.widget() == null) throw ApplicationException.validation(
                new com.yumpoo.platform.foundation.application.error.FieldViolation("widget", "REQUIRED", "图表不能为空"));
        validateQueryWidgets(List.of(query.widget()));
        return statistics.table(actor, dashboard.configuration().projectIds(),
                query.filters() == null ? dashboard.configuration().filters() : query.filters(),
                DashboardCharts.request(query.widget()), query.selection(), query.projectId(), query.table());
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
    private void validateQueryWidgets(List<Widget> widgets) {
        if (widgets.size() > 40 || widgets.stream().anyMatch(w -> w == null || w.kind() == null || w.metric() == null || w.grouping() == null || w.sort() == null))
            throw ApplicationException.validation(new com.yumpoo.platform.foundation.application.error.FieldViolation("widgets", "INVALID_VALUE", "图表配置无效"));
        for (var w : widgets) {
            if (!java.util.Set.of("METRIC", "STATUS", "PROJECT_WORKLOAD", "MEMBER_WORKLOAD", "PROJECT_TIME", "CHART").contains(w.kind())
                    || w.kind().equals("CHART") && w.chart() == null)
                throw ApplicationException.validation(new com.yumpoo.platform.foundation.application.error.FieldViolation("widgets", "INVALID_VALUE", "图表配置无效"));
            DashboardCharts.validate(w, statistics);
        }
    }
}
