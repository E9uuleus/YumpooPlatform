package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.ChartStatistics;
import com.yumpoo.platform.workitem.application.WorkItemChartRepository;
import com.yumpoo.platform.workitem.application.WorkItemStatisticsRepository;
import com.yumpoo.platform.workitem.domain.StatisticsFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcWorkItemChartRepository implements WorkItemChartRepository {
    private final JdbcClient jdbc;
    public JdbcWorkItemChartRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public List<UUID> matchingIds(UUID company, ChartStatistics.TableScope scope, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var base = base(company, scope.global(), scope.local(), scope.chart(), scope.selection(), scope.asOf(), true);
        return jdbc.sql(base.sql() + "SELECT id FROM scoped WHERE id IN (:ids)")
                .params(base.parameters()).param("ids", ids).query(UUID.class).list();
    }
    public Map<UUID, Long> childCounts(UUID company, ChartStatistics.TableScope scope, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var base = base(company, scope.global(), scope.local(), scope.chart(), scope.selection(), scope.asOf(), true);
        var counts = new java.util.HashMap<UUID, Long>();
        jdbc.sql(base.sql() + "SELECT r.left_work_item_id AS parent, COUNT(*) AS count FROM yumpoo.work_item_relation r "
                + "JOIN scoped child ON child.id=r.right_work_item_id WHERE r.company_id=:company "
                + "AND r.relation_type='PARENT_CHILD' AND r.deleted_at IS NULL AND r.left_work_item_id IN (:ids) GROUP BY r.left_work_item_id")
                .params(base.parameters()).param("ids", ids).query((rs,n) -> {
                    counts.put(rs.getObject("parent", UUID.class), rs.getLong("count")); return 0;
                }).list();
        return counts;
    }
    private record Dimension(String key, String label, String color, boolean member) {
        Dimension(String key, String label, String color) { this(key, label, color, false); }
    }

    public List<ChartStatistics.Point> aggregate(UUID company, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, Instant asOf) {
        var base = base(company, global, local, chart, null, asOf, false);
        var x = dimension(chart.dimension()); var series = dimension(chart.series());
        boolean fan = x.member() || series.member();
        String source = fan ? "member_rows" : "scoped";
        String duration = fan ? "member_duration_ms" : "duration_ms";
        String memberRows = fan ? JdbcWorkItemStatisticsRepository.memberRows("scoped", true,
                java.util.stream.Stream.of("assignees", "local_assignees").filter(base.parameters()::containsKey).toArray(String[]::new)) : "";
        String sql = ",category_totals AS (SELECT " + x.key() + " AS category_key,"
                + measure(chart.measure(), x.member() ? "member_duration_ms" : "duration_ms")
                + " AS category_value FROM " + (x.member() ? "member_rows" : "scoped") + " GROUP BY 1) SELECT "
                + x.key() + " AS key,MAX(" + x.label() + ") AS label,MAX(" + x.color() + ") AS color,"
                + series.key() + " AS series_key,MAX(" + series.label() + ") AS series_label,MAX(" + series.color() + ") AS series_color,"
                + "COUNT(*) AS count," + measure(chart.measure(), duration) + " AS value," + measure(chart.xMeasure(), duration) + " AS x_value,"
                + measure(chart.sizeMeasure(), duration) + " AS size_value,MAX(category_value) AS category_value,MIN(project_id::text) AS project_hint FROM "
                + source + " JOIN category_totals ON " + x.key() + "=category_key GROUP BY 1,4 ORDER BY 1,4";
        return jdbc.sql(base.sql() + memberRows + sql).params(base.parameters()).query((rs, n) -> new ChartStatistics.Point(
                rs.getString("key"), rs.getString("label"), rs.getString("color"), rs.getString("series_key"),
                rs.getString("series_label"), rs.getString("series_color"), rs.getLong("count"), rs.getDouble("value"),
                rs.getDouble("x_value"), rs.getDouble("size_value"), rs.getDouble("category_value"), rs.getString("project_hint"))).list();
    }
    public List<WorkItemStatisticsRepository.Item> items(UUID company, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, Instant asOf, int offset, int limit) {
        var base = base(company, global, local, chart, selection, asOf, true);
        return jdbc.sql(base.sql() + "SELECT * FROM scoped ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset")
                .params(base.parameters()).param("offset", offset).param("limit", limit).query(JdbcWorkItemStatisticsRepository::item).list();
    }
    public long count(UUID company, StatisticsFilter global, StatisticsFilter local, ChartStatistics.Request chart,
            ChartStatistics.Selection selection, Instant asOf) {
        var base = base(company, global, local, chart, selection, asOf, true);
        return jdbc.sql(base.sql() + "SELECT COUNT(*) FROM scoped").params(base.parameters()).query(Long.class).single();
    }
    static JdbcWorkItemStatisticsRepository.Query base(UUID company, StatisticsFilter global, StatisticsFilter local,
            ChartStatistics.Request chart, ChartStatistics.Selection selection, Instant asOf, boolean details) {
        var base = JdbcWorkItemStatisticsRepository.base(company, global, asOf, true);
        Map<String, Object> p = base.parameters();
        p.put("interval", chart.dateInterval().toLowerCase(java.util.Locale.ROOT)); p.put("timezone", chart.timezone());
        List<String> clauses = new ArrayList<>();
        if (details && chart.xMeasure() == null) {
            switch (chart.measure().metric()) {
                case "DONE" -> clauses.add("status_category='DONE'");
                case "IN_PROGRESS" -> clauses.add("status_category='IN_PROGRESS'");
                case "COMPLETION_RATE" -> { if (chart.dimension().equals("NONE")) clauses.add("status_category='DONE'"); }
                case "DURATION" -> { if (chart.measure().calculation().equals("SUM")) clauses.add("session_count>0"); }
                default -> { }
            }
        }
        if (!local.includeArchived()) clauses.add("NOT archived");
        if (!local.assignees().isEmpty()) {
            clauses.add(JdbcWorkItemStatisticsRepository.assigneeMatch("local_assignees"));
            p.put("local_assignees", local.assignees());
        }
        add(clauses, p, "project_id::text||':'||status_code", "statuses", local.statuses());
        add(clauses, p, "project_id::text||':'||COALESCE(priority,'UNASSIGNED')", "priorities", local.priorities());
        add(clauses, p, "content_id", "contents", local.contentIds());
        add(clauses, p, "status_category", "categories", local.categories());
        if (local.dueFrom() != null) { clauses.add("due_date>=:localDueFrom"); p.put("localDueFrom", local.dueFrom()); }
        if (local.dueTo() != null) { clauses.add("due_date<=:localDueTo"); p.put("localDueTo", local.dueTo()); }
        if (local.hasTime()) clauses.add("session_count>0");
        if (!local.query().isEmpty()) {
            clauses.add("(title ILIKE :localQuery ESCAPE '\\' OR item_no ILIKE :localQuery ESCAPE '\\')");
            p.put("localQuery", "%" + local.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        var x = dimension(chart.dimension()); var s = dimension(chart.series());
        if (!chart.showEmpty()) { clauses.add(x.member() ? "cardinality(assignee_ids)>0" : x.key() + "<>'EMPTY'"); clauses.add(s.member() ? "cardinality(assignee_ids)>0" : s.key() + "<>'EMPTY'"); }
        if (selection != null && selection.key() != null) { clauses.add(x.member() ? "(CASE WHEN :selectedKey='EMPTY' THEN cardinality(assignee_ids)=0 ELSE :selectedKey=ANY(assignee_ids::text[]) END)" : x.key() + "=:selectedKey"); p.put("selectedKey", selection.key()); }
        if (selection != null && selection.seriesKey() != null) { clauses.add(s.member() ? "(CASE WHEN :selectedSeries='EMPTY' THEN cardinality(assignee_ids)=0 ELSE :selectedSeries=ANY(assignee_ids::text[]) END)" : s.key() + "=:selectedSeries"); p.put("selectedSeries", selection.seriesKey()); }
        return new JdbcWorkItemStatisticsRepository.Query(base.sql() + ",scoped AS (SELECT * FROM filtered WHERE "
                + (clauses.isEmpty() ? "TRUE" : String.join(" AND ", clauses)) + ") ", p);
    }
    private static void add(List<String> clauses, Map<String, Object> p, String column, String key, List<?> values) {
        if (!values.isEmpty()) { clauses.add(column + " IN (:local_" + key + ")"); p.put("local_" + key, values); }
    }
    private static String measure(ChartStatistics.Measure m, String durationColumn) {
        if (m == null) return "0";
        return switch (m.metric()) {
            case "TOTAL" -> "COUNT(*)";
            case "IN_PROGRESS" -> "COUNT(*) FILTER(WHERE status_category='IN_PROGRESS')";
            case "DONE" -> "COUNT(*) FILTER(WHERE status_category='DONE')";
            case "COMPLETION_RATE" -> "100.0*COUNT(*) FILTER(WHERE status_category='DONE')/NULLIF(COUNT(*),0)";
            case "DURATION" -> switch (m.calculation()) {
                case "SUM" -> "SUM(" + durationColumn + ")"; case "AVG" -> "AVG(" + durationColumn + ")";
                case "MEDIAN" -> "percentile_cont(0.5) WITHIN GROUP(ORDER BY " + durationColumn + ")";
                case "MIN" -> "MIN(" + durationColumn + ")"; case "MAX" -> "MAX(" + durationColumn + ")";
                default -> throw new IllegalArgumentException("Invalid calculation");
            };
            default -> throw new IllegalArgumentException("Invalid metric");
        };
    }
    private static Dimension dimension(String name) {
        return switch (name) {
            case "NONE" -> new Dimension("'all'::text", "'全部'::text", "'BRIGHT_BLUE'::text");
            case "PROJECT" -> new Dimension("project_id::text", "project_id::text", "'BRIGHT_BLUE'::text");
            case "CONTENT" -> new Dimension("COALESCE(content_id::text,'EMPTY')", "COALESCE(content_name,'未设置')", "COALESCE(content_color,'GRAY')");
            case "STATUS" -> new Dimension("jsonb_build_array(status_code,status_category,status_name,status_color)::text", "status_name", "status_color");
            case "PRIORITY" -> new Dimension("CASE WHEN priority IS NULL THEN 'EMPTY' ELSE jsonb_build_array(priority,priority_name,priority_color)::text END", "priority_name", "priority_color");
            case "CATEGORY" -> new Dimension("status_category", "status_category", "'GRAY'::text");
            case "ASSIGNEE" -> new Dimension("COALESCE(member_id::text,'EMPTY')", "COALESCE(member_id::text,'EMPTY')", "'BRIGHT_BLUE'::text", true);
            case "REPORTER" -> new Dimension("COALESCE(reporter_user_id::text,'EMPTY')", "COALESCE(reporter_user_id::text,'EMPTY')", "'PURPLE'::text");
            default -> {
                String column = switch (name) {
                    case "CREATED" -> "created_at AT TIME ZONE :timezone"; case "UPDATED" -> "updated_at AT TIME ZONE :timezone";
                    case "COMPLETED" -> "completed_at AT TIME ZONE :timezone"; case "DUE" -> "due_date::timestamp";
                    case "TIMELINE_START" -> "timeline_start_date::timestamp"; case "TIMELINE_END" -> "timeline_end_date::timestamp";
                    default -> throw new IllegalArgumentException("Invalid dimension");
                };
                String key = "COALESCE(date_trunc(:interval," + column + ")::date::text,'EMPTY')";
                yield new Dimension(key, key, "'BRIGHT_BLUE'::text");
            }
        };
    }
}
