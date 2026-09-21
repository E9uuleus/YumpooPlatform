package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.workitem.api.WorkItemStatisticsQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.yumpoo.platform.reporting.application.DashboardModels.*;

final class DashboardValidation {
    private DashboardValidation() {}
    static Write normalize(Write input, WorkItemStatisticsQuery statistics) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().strip().length() > 100)
            throw invalid("name", "请输入 1–100 字的仪表板名称");
        Configuration c = input.configuration();
        if (c == null || c.projectIds() == null || c.widgets() == null || c.projectIds().size() > 100 || c.widgets().size() > 40
                || c.projectIds().stream().anyMatch(java.util.Objects::isNull))
            throw invalid("configuration", "最多连接 100 个项目、添加 40 个组件");
        Set<String> ids = new HashSet<>();
        for (Widget w : c.widgets()) {
            if (w == null || w.id() == null || !ids.add(w.id())) throw invalid("widgets", "组件标识重复或缺失");
            try { UUID.fromString(w.id()); } catch (IllegalArgumentException ex) { throw invalid("widgets", "组件标识无效"); }
            if (w.kind() == null || !Set.of("METRIC", "STATUS", "PROJECT_WORKLOAD", "MEMBER_WORKLOAD", "PROJECT_TIME", "CHART").contains(w.kind())
                    || w.title() == null || w.title().isBlank() || w.title().length() > 80
                    || w.metric() == null || !Set.of("TOTAL", "IN_PROGRESS", "DONE", "COMPLETION_RATE", "DURATION").contains(w.metric())
                    || w.grouping() == null || !Set.of("STATUS", "CATEGORY").contains(w.grouping())
                    || w.sort() == null || !Set.of("DESC", "ASC").contains(w.sort()))
                throw invalid("widgets", "组件类型或设置无效");
            if (w.kind().equals("CHART") && w.chart() == null) throw invalid("chart", "自定义图表需要图表设置");
            DashboardCharts.validate(w, statistics);
            position(w.wide(), 12, w.kind().equals("METRIC"));
            position(w.medium(), 6, w.kind().equals("METRIC"));
        }
        overlaps(c.widgets().stream().map(Widget::wide).toList());
        overlaps(c.widgets().stream().map(Widget::medium).toList());
        var filters = c.filters() == null ? WorkItemStatisticsQuery.Filters.empty() : c.filters();
        statistics.validate(filters);
        filters = new WorkItemStatisticsQuery.Filters(values(filters.projectIds()), values(filters.assignees()), values(filters.statuses()),
                values(filters.priorities()), values(filters.contentIds()), values(filters.categories()), filters.dueFrom(), filters.dueTo(),
                filters.includeArchived(), filters.query() == null ? "" : filters.query().strip(), filters.hasTime());
        return new Write(input.name().strip(), new Configuration(c.projectIds().stream().distinct().toList(), List.copyOf(c.widgets()), filters));
    }
    private static <T> List<T> values(List<T> values) { return values == null ? List.of() : values.stream().distinct().toList(); }
    private static void position(Position p, int columns, boolean metric) {
        if (p == null || p.x() < 0 || p.y() < 0 || p.y() > 10000 || p.w() < 1
                || p.h() < 3 || p.h() > 100 || p.w() > columns || p.x() > columns - p.w())
            throw invalid("widgets", "组件位置或尺寸超出画布范围");
    }
    private static void overlaps(List<Position> positions) {
        for (int i = 0; i < positions.size(); i++) for (int j = i + 1; j < positions.size(); j++) {
            Position a = positions.get(i), b = positions.get(j);
            if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h())
                throw invalid("widgets", "组件不能相互重叠");
        }
    }
    private static ApplicationException invalid(String field, String message) {
        return ApplicationException.validation(new FieldViolation(field, "INVALID_VALUE", message));
    }
}
