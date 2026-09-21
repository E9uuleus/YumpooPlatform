package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemStatisticsRepository;
import com.yumpoo.platform.workitem.application.WorkItemStatisticsService;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class WorkItemStatisticsAdapter implements WorkItemStatisticsQuery {
    private final WorkItemStatisticsService service;
    private final com.yumpoo.platform.workitem.application.WorkItemChartService charts;
    private final com.yumpoo.platform.workitem.application.WorkItemService workItems;
    public WorkItemStatisticsAdapter(WorkItemStatisticsService service, com.yumpoo.platform.workitem.application.WorkItemChartService charts,
            com.yumpoo.platform.workitem.application.WorkItemService workItems) { this.service = service; this.charts = charts; this.workItems = workItems; }
    public Snapshot aggregate(CurrentActor actor, List<UUID> projects, Filters filters) {
        var result = service.aggregate(actor, projects, filter(filters));
        return new Snapshot(result.buckets().stream().map(WorkItemStatisticsAdapter::bucket).toList(),
                result.options().stream().map(WorkItemStatisticsAdapter::bucket).toList(), result.asOf());
    }
    public Page items(CurrentActor actor, List<UUID> projects, Filters filters, int offset, int limit) {
        var result = service.items(actor, projects, filter(filters), offset, limit);
        return page(actor, result);
    }
    private Page page(CurrentActor actor, WorkItemStatisticsService.Page result) {
        var byProject = result.items().stream().map(WorkItemStatisticsService.ItemRow::item)
                .collect(java.util.stream.Collectors.groupingBy(WorkItemStatisticsRepository.Item::projectId,
                        java.util.stream.Collectors.mapping(WorkItemStatisticsRepository.Item::id, java.util.stream.Collectors.toList())));
        var editable = new java.util.HashMap<UUID, com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemListItem>();
        byProject.forEach((project, ids) -> workItems.tableItemsByIds(actor, project, ids, result.asOf())
                .forEach(item -> editable.put(item.id(), item)));
        return new Page(result.items().stream().map(row -> {
            var i = row.item();
            return new Item(i.id(), i.projectId(), i.itemNo(), i.title(), i.assigneeUserId(), row.assigneeName(),
                    i.statusCode(), i.statusName(), i.statusCategory(), i.colorToken(), i.durationMs(), i.updatedAt(),
                    i.contentId(), i.contentName(), i.priority(), i.priorityName(), i.reporterUserId(), row.reporterName(),
                    i.dueDate(), i.timelineStartDate(), i.timelineEndDate(), i.createdAt(), i.completedAt(), editable.get(i.id()));
        }).toList(), result.totalElements(), result.asOf());
    }
    public void validate(Filters filters) { service.validate(filter(filters)); }
    public void validateChart(ChartRequest chart) { charts.validate(chart(chart)); }
    public List<ChartResult> charts(CurrentActor actor, List<UUID> projects, Filters filters, List<ChartRequest> requests, java.time.Instant asOf) {
        return charts.charts(actor, projects, filter(filters), requests.stream().map(WorkItemStatisticsAdapter::chart).toList(), asOf).stream()
                .map(r -> new ChartResult(r.id(), r.points().stream().map(p -> new ChartPoint(p.key(), p.label(), p.colorToken(),
                        p.seriesKey(), p.seriesLabel(), p.seriesColorToken(), p.count(), p.value(), p.xValue(), p.sizeValue(), p.categoryValue())).toList())).toList();
    }
    public Page chartItems(CurrentActor actor, List<UUID> projects, Filters filters, ChartRequest request, ChartSelection selection, int offset, int limit) {
        return page(actor, charts.items(actor, projects, filter(filters), chart(request), selection == null ? null
                : new com.yumpoo.platform.workitem.application.ChartStatistics.Selection(selection.key(), selection.seriesKey()), offset, limit));
    }
    public TablePage table(CurrentActor actor, List<UUID> projects, Filters filters, ChartRequest chart,
            ChartSelection selection, UUID projectId, TableCriteria c) {
        if (c == null) throw com.yumpoo.platform.foundation.application.error.ApplicationException.validation(
                new com.yumpoo.platform.foundation.application.error.FieldViolation("table", "REQUIRED", "表格查询不能为空"));
        var scope = charts.tableScope(actor, projects, filter(filters), chart(chart), selection == null ? null
                : new com.yumpoo.platform.workitem.application.ChartStatistics.Selection(selection.key(), selection.seriesKey()), projectId);
        var request = new com.yumpoo.platform.workitem.application.WorkItemQuery.Request(c.q(), c.status(), c.priority(),
                c.assigneeUserId(), c.contentId(), c.dueFrom(), c.dueTo(), c.updatedAfter(), c.sort(),
                new com.yumpoo.platform.workitem.application.WorkItemQuery.TimeFilter(c.timeTrackingState(),
                        c.timeTrackingMinMs(), c.timeTrackingMaxMs(), scope.asOf(), 0, 0), c.emptyField()).withScope(scope);
        var page = com.yumpoo.platform.foundation.api.pagination.CursorPageRequest.of(c.cursor(), c.limit());
        if (c.field() != null) {
            var result = workItems.listProjectFilterOptions(actor, projectId, c.field(), request, page);
            return new TablePage(List.of(), result.items(), result.nextCursor(), List.of(), java.util.Map.of());
        }
        List<com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemListItem> items;
        String next = null;
        if (c.parentWorkItemId() != null) {
            // Resolve the parent through this project before allowing a cross-module subitem query.
            if (workItems.tableItemsByIds(actor, projectId, List.of(c.parentWorkItemId()), scope.asOf()).isEmpty())
                throw new com.yumpoo.platform.foundation.application.error.ApplicationException(
                        com.yumpoo.platform.foundation.application.error.StandardErrorCode.RESOURCE_NOT_FOUND);
            items = workItems.listSubitems(actor, c.parentWorkItemId(), request).items();
        } else {
            var result = workItems.listProject(actor, projectId, request, "TABLE", page);
            items = result.items(); next = result.nextCursor();
        }
        var ids = items.stream().map(com.yumpoo.platform.workitem.application.WorkItemModels.ProjectWorkItemListItem::id).toList();
        var matching = new java.util.HashSet<>(charts.matchingIds(actor, scope, ids));
        var childCounts = charts.childCounts(actor, scope, ids);
        return new TablePage(items, List.of(), next, ids.stream().filter(i -> !matching.contains(i)).toList(), childCounts);
    }
    private static com.yumpoo.platform.workitem.application.ChartStatistics.Request chart(ChartRequest r) {
        if (r == null) return null;
        return new com.yumpoo.platform.workitem.application.ChartStatistics.Request(r.id(), r.dimension(), r.series(), r.dateInterval(), r.timezone(),
                measure(r.measure()), measure(r.xMeasure()), measure(r.sizeMeasure()), r.filters() == null ? null : filter(r.filters()), r.projectIds(), r.showEmpty());
    }
    private static com.yumpoo.platform.workitem.application.ChartStatistics.Measure measure(ChartMeasure m) {
        return m == null ? null : new com.yumpoo.platform.workitem.application.ChartStatistics.Measure(m.metric(), m.calculation());
    }
    private static WorkItemStatisticsService.Request filter(Filters value) {
        Filters f = value == null ? Filters.empty() : value;
        return new WorkItemStatisticsService.Request(f.projectIds(), f.assignees(), f.statuses(), f.priorities(), f.contentIds(),
                f.categories(), f.dueFrom(), f.dueTo(), f.includeArchived(), f.query(), f.hasTime());
    }
    private static Bucket bucket(WorkItemStatisticsRepository.Bucket b) {
        return new Bucket(b.kind(), b.key(), b.projectId(), b.userId(), b.label(), b.code(), b.category(),
                b.colorToken(), b.count(), b.inProgress(), b.done(), b.durationMs());
    }
}
