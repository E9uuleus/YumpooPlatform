package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.Filters;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.ChartMeasure;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.ChartSelection;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.ChartResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DashboardModels {
    private DashboardModels() {}
    public record Position(int x, int y, int w, int h) {}
    public record Widget(String id, String kind, String title, String metric, String grouping,
            String sort, boolean showLegend, boolean showValues, Position wide, Position medium, Chart chart) {}
    public record LabelOverride(String key, String name, String color, int order) {}
    public record Chart(String type, String dimension, String series, String dateInterval, String timezone,
            ChartMeasure measure, ChartMeasure xMeasure, ChartMeasure sizeMeasure, boolean stacked,
            boolean showLegend, boolean showValues, String valueFormat, String sort, int limit, boolean showEmpty,
            List<UUID> projectIds, Filters filters, List<LabelOverride> labels, List<String> detailColumns) {}
    public record Configuration(List<UUID> projectIds, List<Widget> widgets, Filters filters) {}
    public record Write(String name, Configuration configuration) {}
    public record Stored(UUID id, UUID companyId, UUID ownerUserId, String name, Configuration configuration,
            long version, Instant createdAt, Instant updatedAt, Instant deletedAt) {}
    public record Connection(UUID id, String name, String code, String lifecycle, boolean available) {}
    public record View(UUID id, String name, Configuration configuration, List<Connection> projects,
            long version, String etag, Instant updatedAt) {}
    public record Summary(UUID id, String name, int projectCount, int widgetCount, Instant updatedAt) {}
    public record ListResponse(List<Summary> items) {}
    public record Query(Filters filters, List<Widget> widgets) {}
    public record QueryResponse(List<com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.Bucket> buckets,
            List<com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.Bucket> options, Instant asOf, List<Connection> projects, List<ChartResult> charts) {}
    public record TableQuery(Filters filters, Widget widget, ChartSelection selection, UUID projectId,
            com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery.TableCriteria table) {}
    public record ItemsQuery(Filters filters, int offset, int limit, Widget widget, ChartSelection selection) {}
}
