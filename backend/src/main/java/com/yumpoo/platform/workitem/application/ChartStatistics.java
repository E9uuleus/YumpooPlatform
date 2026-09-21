package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChartStatistics {
    private ChartStatistics() {}
    public static final Set<String> DIMENSIONS = Set.of("NONE", "PROJECT", "CONTENT", "STATUS", "CATEGORY", "PRIORITY",
            "ASSIGNEE", "REPORTER", "CREATED", "UPDATED", "COMPLETED", "DUE", "TIMELINE_START", "TIMELINE_END");
    public record Measure(String metric, String calculation) {}
    public record Request(String id, String dimension, String series, String dateInterval, String timezone,
            Measure measure, Measure xMeasure, Measure sizeMeasure, WorkItemStatisticsService.Request filters,
            List<UUID> projectIds, boolean showEmpty) {}
    public record Selection(String key, String seriesKey) {}
    public record TableScope(com.yumpoo.platform.workitem.domain.StatisticsFilter global,
            com.yumpoo.platform.workitem.domain.StatisticsFilter local, Request chart, Selection selection,
            java.time.Instant asOf) {
        public TableScope at(java.time.Instant time) { return new TableScope(global, local, chart, selection, time); }
        public String fingerprint() { return global + "|" + local + "|" + chart + "|" + selection; }
    }
    public record Point(String key, String label, String colorToken, String seriesKey, String seriesLabel,
            String seriesColorToken, long count, double value, double xValue, double sizeValue, double categoryValue, String projectHint) {}
    public record Result(String id, List<Point> points) {}

    public static void validate(Request r) {
        if (r == null || r.id() == null || r.id().length() > 80 || r.dimension() == null || !DIMENSIONS.contains(r.dimension())
                || r.series() == null || !DIMENSIONS.contains(r.series()) || r.dateInterval() == null
                || !Set.of("DAY", "WEEK", "MONTH").contains(r.dateInterval()) || r.timezone() == null
                || r.projectIds() != null && (r.projectIds().size() > 100 || r.projectIds().stream().anyMatch(java.util.Objects::isNull))) invalid();
        try { ZoneId.of(r.timezone()); } catch (java.time.DateTimeException e) { invalid(); }
        measure(r.measure());
        if (r.xMeasure() != null) measure(r.xMeasure());
        if (r.sizeMeasure() != null) measure(r.sizeMeasure());
    }
    private static void measure(Measure m) {
        if (m == null || m.metric() == null || !Set.of("TOTAL", "IN_PROGRESS", "DONE", "COMPLETION_RATE", "DURATION").contains(m.metric())
                || m.calculation() == null || !Set.of("SUM", "AVG", "MEDIAN", "MIN", "MAX").contains(m.calculation())) invalid();
    }
    private static void invalid() { throw ApplicationException.validation(new FieldViolation("chart", "INVALID_VALUE", "图表设置无效")); }
}
