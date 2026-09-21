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
import java.util.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WorkItemChartService {
    private final WorkItemChartRepository repository;
    private final MemberProjectQuery projects;
    private final MinimalUserSnapshotQuery users;
    private final Clock clock;
    public WorkItemChartService(WorkItemChartRepository repository, MemberProjectQuery projects, MinimalUserSnapshotQuery users, Clock clock) {
        this.repository = repository; this.projects = projects; this.users = users; this.clock = clock;
    }
    public void validate(ChartStatistics.Request request) { ChartStatistics.validate(request); local(request); }
    private static StatisticsFilter local(ChartStatistics.Request r) {
        return r.filters() == null ? new StatisticsFilter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, true, "", false)
                : r.filters().filter();
    }
    private record Scope(StatisticsFilter global, StatisticsFilter local) {}
    private static Scope scope(List<MemberProjectQuery.Project> allowed, WorkItemStatisticsService.Request global, ChartStatistics.Request chart) {
        var g = global.filter(); var l = local(chart);
        var ids = allowed.stream().map(MemberProjectQuery.Project::id)
                .filter(id -> g.projectIds().isEmpty() || g.projectIds().contains(id))
                .filter(id -> l.projectIds().isEmpty() || l.projectIds().contains(id))
                .filter(id -> chart.projectIds() == null || chart.projectIds().contains(id)).toList();
        return new Scope(g.within(ids), l);
    }
    public List<ChartStatistics.Result> charts(CurrentActor actor, List<UUID> connected, WorkItemStatisticsService.Request global,
            List<ChartStatistics.Request> requests, Instant asOf) {
        global.filter();
        if (requests == null || requests.size() > 40) throw invalid("最多查询 40 个图表");
        var allowed = projects.find(actor, connected);
        var output = new ArrayList<ChartStatistics.Result>();
        var cache = new HashMap<ChartStatistics.Request, List<ChartStatistics.Point>>();
        Set<String> ids = new HashSet<>();
        for (var chart : requests) {
            validate(chart);
            if (!ids.add(chart.id())) throw invalid("图表标识重复");
            var scoped = scope(allowed, global, chart);
            var signature = new ChartStatistics.Request("", chart.dimension(), chart.series(), chart.dateInterval(), chart.timezone(),
                    chart.measure(), chart.xMeasure(), chart.sizeMeasure(), chart.filters(), chart.projectIds(), chart.showEmpty());
            var points = scoped.global().projectIds().isEmpty() ? List.<ChartStatistics.Point>of() : cache.computeIfAbsent(signature,
                    ignored -> repository.aggregate(actor.companyId(), scoped.global(), scoped.local(), chart, asOf));
            output.add(new ChartStatistics.Result(chart.id(), points));
        }
        Set<UUID> peopleIds = new HashSet<>();
        for (int i = 0; i < requests.size(); i++) {
            var r = requests.get(i);
            for (var p : output.get(i).points()) {
                personId(r.dimension(), p.key(), peopleIds); personId(r.series(), p.seriesKey(), peopleIds);
            }
        }
        var people = users.findByUserIds(actor.companyId(), List.copyOf(peopleIds));
        Map<String, String> names = new HashMap<>(); allowed.forEach(p -> names.put(p.id().toString(), p.name()));
        var result = new ArrayList<ChartStatistics.Result>();
        for (int i = 0; i < requests.size(); i++) {
            var r = requests.get(i); var rows = output.get(i).points();
            var ambiguousLabels = ambiguousLabels(rows, false); var ambiguousSeries = ambiguousLabels(rows, true);
            result.add(new ChartStatistics.Result(r.id(), rows.stream().map(p -> new ChartStatistics.Point(p.key(),
                    distinctLabel(r.dimension(), p, ambiguousLabels, false, people, names), color(r.dimension(), p.key(), p.colorToken()),
                    p.seriesKey(), distinctLabel(r.series(), p, ambiguousSeries, true, people, names), color(r.series(), p.seriesKey(), p.seriesColorToken()),
                    p.count(), p.value(), p.xValue(), p.sizeValue(), p.categoryValue(), p.projectHint())).toList()));
        }
        return result;
    }
    public WorkItemStatisticsService.Page items(CurrentActor actor, List<UUID> connected, WorkItemStatisticsService.Request global,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, int offset, int limit) {
        validate(chart);
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 100) throw invalid("分页范围无效");
        if (selection != null && (selection.key() != null && selection.key().length() > 2000
                || selection.seriesKey() != null && selection.seriesKey().length() > 2000)) throw invalid("图表分类无效");
        var s = scope(projects.find(actor, connected), global, chart); var now = clock.instant();
        if (s.global().projectIds().isEmpty()) return new WorkItemStatisticsService.Page(List.of(), 0, now);
        var rows = repository.items(actor.companyId(), s.global(), s.local(), chart, selection, now, offset, limit);
        var people = users.findByUserIds(actor.companyId(), rows.stream().flatMap(r -> java.util.stream.Stream.of(r.assigneeUserId(), r.reporterUserId()))
                .filter(Objects::nonNull).distinct().toList());
        return new WorkItemStatisticsService.Page(rows.stream().map(r -> new WorkItemStatisticsService.ItemRow(r,
                person(r.assigneeUserId(), people), person(r.reporterUserId(), people))).toList(),
                repository.count(actor.companyId(), s.global(), s.local(), chart, selection, now), now);
    }
    public ChartStatistics.TableScope tableScope(CurrentActor actor, List<UUID> connected, WorkItemStatisticsService.Request global,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, UUID projectId) {
        validate(chart);
        if (selection != null && (selection.key() != null && selection.key().length() > 2000
                || selection.seriesKey() != null && selection.seriesKey().length() > 2000)) throw invalid("图表分类无效");
        var scoped = scope(projects.find(actor, connected), global, chart);
        if (projectId == null || !scoped.global().projectIds().contains(projectId))
            throw new ApplicationException(com.yumpoo.platform.foundation.application.error.StandardErrorCode.RESOURCE_NOT_FOUND);
        return new ChartStatistics.TableScope(scoped.global().within(List.of(projectId)), scoped.local(), chart, selection, clock.instant());
    }
    public List<UUID> matchingIds(CurrentActor actor, ChartStatistics.TableScope scope, List<UUID> ids) {
        return repository.matchingIds(actor.companyId(), scope, ids);
    }
    public Map<UUID, Long> childCounts(CurrentActor actor, ChartStatistics.TableScope scope, List<UUID> ids) {
        return repository.childCounts(actor.companyId(), scope, ids);
    }
    private static void personId(String dimension, String key, Set<UUID> ids) {
        if (Set.of("ASSIGNEE", "REPORTER").contains(dimension) && !key.equals("EMPTY")) ids.add(UUID.fromString(key));
    }
    private static Set<String> ambiguousLabels(List<ChartStatistics.Point> points, boolean series) {
        Map<String, Set<String>> keys = new HashMap<>();
        for (var p : points) keys.computeIfAbsent(series ? p.seriesLabel() : p.label(), ignored -> new HashSet<>()).add(series ? p.seriesKey() : p.key());
        Set<String> result = new HashSet<>(); keys.forEach((name, values) -> { if (values.size() > 1) result.add(name); }); return result;
    }
    private static String distinctLabel(String dimension, ChartStatistics.Point point, Set<String> ambiguousLabels,
            boolean series, Map<UUID, MinimalUserSnapshot> people, Map<String, String> projects) {
        String key = series ? point.seriesKey() : point.key(), raw = series ? point.seriesLabel() : point.label();
        String label = label(dimension, key, raw, people, projects);
        boolean ambiguous = Set.of("STATUS", "PRIORITY", "CONTENT").contains(dimension) && ambiguousLabels.contains(raw);
        return ambiguous ? label + " · " + projects.getOrDefault(point.projectHint(), "项目") : label;
    }
    private static String person(UUID id, Map<UUID, MinimalUserSnapshot> people) {
        return id == null ? "未分配" : people.containsKey(id) ? people.get(id).displayName() : "历史成员";
    }
    private static String label(String dimension, String key, String label, Map<UUID, MinimalUserSnapshot> people, Map<String, String> projects) {
        if (key.equals("EMPTY")) return "未设置";
        if (Set.of("ASSIGNEE", "REPORTER").contains(dimension)) return person(UUID.fromString(key), people);
        if (dimension.equals("PROJECT")) return projects.getOrDefault(key, "项目");
        if (dimension.equals("CATEGORY")) return Map.of("TODO", "待开始", "IN_PROGRESS", "进行中", "DONE", "已完成", "CANCELED", "已取消").getOrDefault(key, key);
        return label;
    }
    private static String color(String dimension, String key, String color) {
        return dimension.equals("CATEGORY") ? Map.of("TODO", "GRAY", "IN_PROGRESS", "ORANGE", "DONE", "GREEN", "CANCELED", "RED").getOrDefault(key, "GRAY") : color;
    }
    private static ApplicationException invalid(String message) { return ApplicationException.validation(new FieldViolation("chart", "INVALID_VALUE", message)); }
}
