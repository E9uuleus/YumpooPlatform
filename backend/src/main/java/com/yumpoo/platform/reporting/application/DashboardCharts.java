package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import java.util.List;
import java.util.Set;
import static com.yumpoo.platform.reporting.application.DashboardModels.*;

final class DashboardCharts {
    private DashboardCharts() {}
    static Chart resolve(Widget w) {
        if (w.chart() != null) return w.chart();
        String type = switch (w.kind()) { case "METRIC" -> "NUMBER"; case "STATUS" -> "DONUT"; default -> "BAR"; };
        String dimension = switch (w.kind()) {
            case "METRIC" -> "NONE"; case "STATUS" -> w.grouping(); case "MEMBER_WORKLOAD" -> "ASSIGNEE"; default -> "PROJECT";
        };
        String metric = w.kind().equals("METRIC") ? w.metric() : w.kind().equals("PROJECT_TIME") ? "DURATION" : "TOTAL";
        return new Chart(type, dimension, w.kind().equals("PROJECT_WORKLOAD") ? w.grouping() : "NONE", "MONTH", "UTC",
                new WorkItemStatisticsQuery.ChartMeasure(metric, "SUM"), null, null, w.kind().equals("PROJECT_WORKLOAD"),
                w.showLegend(), w.showValues(), w.kind().equals("STATUS") ? "PERCENT" : "VALUE", w.sort().equals("ASC") ? "VALUE_ASC" : "VALUE_DESC", 0, true,
                null, null, List.of(), List.of("project", "content", "assignee", "status", "duration"));
    }
    static WorkItemStatisticsQuery.ChartRequest request(Widget w) {
        Chart c = resolve(w);
        return new WorkItemStatisticsQuery.ChartRequest(w.id(), c.type().equals("NUMBER") ? "NONE" : c.dimension(),
                Set.of("NUMBER", "PIE", "DONUT", "BUBBLE").contains(c.type()) ? "NONE" : c.series(), c.dateInterval(), c.timezone(), c.measure(),
                c.type().equals("BUBBLE") ? c.xMeasure() : null, c.type().equals("BUBBLE") ? c.sizeMeasure() : null, c.filters(), c.projectIds(), c.showEmpty());
    }
    static void validate(Widget w, WorkItemStatisticsQuery statistics) {
        Chart c = resolve(w);
        if (c.type() == null || !Set.of("NUMBER", "COLUMN", "BAR", "LINE", "AREA", "PIE", "DONUT", "BUBBLE").contains(c.type())
                || c.valueFormat() == null || !Set.of("VALUE", "PERCENT").contains(c.valueFormat()) || c.sort() == null
                || !Set.of("VALUE_ASC", "VALUE_DESC", "NAME_ASC", "NAME_DESC", "CUSTOM").contains(c.sort()) || c.limit() < 0 || c.limit() > 5000
                || c.labels() == null || c.labels().size() > 500 || c.detailColumns() == null
                || c.detailColumns().size() > 15 || c.detailColumns().stream().anyMatch(v -> v == null || !Set.of("project", "content", "assignee", "reporter", "status", "priority", "duration", "due", "timeline", "created", "updated", "completed").contains(v))) invalid();
        for (var l : c.labels()) if (l == null || l.key() == null || l.key().length() > 2000 || l.name() == null || l.name().length() > 80
                || l.color() == null || !l.color().matches("|#[a-fA-F0-9]{6}") || l.order() < 0 || l.order() > 5000) invalid();
        if (c.type().equals("BUBBLE") && (c.xMeasure() == null || c.sizeMeasure() == null)) invalid();
        statistics.validateChart(new WorkItemStatisticsQuery.ChartRequest(w.id(), c.dimension(), c.series(), c.dateInterval(), c.timezone(),
                c.measure(), c.xMeasure(), c.sizeMeasure(), c.filters(), c.projectIds(), c.showEmpty()));
    }
    private static void invalid() { throw ApplicationException.validation(new FieldViolation("chart", "INVALID_VALUE", "图表设置无效")); }
}
