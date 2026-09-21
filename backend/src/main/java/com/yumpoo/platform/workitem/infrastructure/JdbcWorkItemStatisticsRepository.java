package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.WorkItemStatisticsRepository;
import com.yumpoo.platform.workitem.domain.StatisticsFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcWorkItemStatisticsRepository implements WorkItemStatisticsRepository {
    private static final String MEASURES = """
        COUNT(*) AS count, COUNT(*) FILTER(WHERE status_category='IN_PROGRESS') AS in_progress,
        COUNT(*) FILTER(WHERE status_category='DONE') AS done,
        COALESCE(SUM(duration_ms),0)::bigint AS duration_ms FROM filtered
        """;
    private static final String TOTAL = "SELECT 'TOTAL' AS kind,'all' AS key,NULL::uuid AS project_id,NULL::uuid AS user_id,"
            + "'' AS label,'' AS code,'' AS category,'GRAY' AS color_token," + MEASURES;
    private static final String PROJECT = "SELECT 'PROJECT',project_id::text,project_id,NULL::uuid,'','','','BLUE',"
            + MEASURES + " GROUP BY project_id";
    private static final String STATUS = "SELECT 'STATUS',project_id::text||':'||status_code,project_id,NULL::uuid,"
            + "MAX(status_name),status_code,MAX(status_category),MAX(status_color)," + MEASURES + " GROUP BY project_id,status_code";
    private static final String MEMBER = "SELECT 'MEMBER',COALESCE(assignee_user_id::text,'UNASSIGNED'),NULL::uuid,assignee_user_id,"
            + "'','','','BLUE'," + MEASURES + " GROUP BY assignee_user_id";
    private static final String PRIORITY = "SELECT 'PRIORITY',project_id::text||':'||COALESCE(priority,'UNASSIGNED'),project_id,NULL::uuid,"
            + "MAX(priority_name),COALESCE(priority,'UNASSIGNED'),'',MAX(priority_color)," + MEASURES + " GROUP BY project_id,priority";
    private static final String CONTENT = "SELECT 'CONTENT',content_id::text,project_id,NULL::uuid,MAX(content_name),'','',MAX(content_color),"
            + MEASURES + " WHERE content_id IS NOT NULL GROUP BY project_id,content_id";
    private final JdbcClient jdbc;
    public JdbcWorkItemStatisticsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Bucket> aggregate(UUID company, StatisticsFilter filter, Instant asOf, boolean options) {
        Query base = base(company, filter, asOf, !options);
        String sql = options ? TOTAL + " UNION ALL " + STATUS + " UNION ALL " + MEMBER + " UNION ALL " + PRIORITY + " UNION ALL " + CONTENT
                : TOTAL + " UNION ALL " + PROJECT + " UNION ALL " + STATUS + " UNION ALL " + MEMBER;
        return jdbc.sql(base.sql() + sql).params(base.parameters()).query((rs, n) -> new Bucket(
                rs.getString("kind"), rs.getString("key"), rs.getObject("project_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("label"), rs.getString("code"),
                rs.getString("category"), rs.getString("color_token"), rs.getLong("count"),
                rs.getLong("in_progress"), rs.getLong("done"), rs.getLong("duration_ms"))).list();
    }
    public List<Item> items(UUID company, StatisticsFilter filter, Instant asOf, int offset, int limit) {
        Query base = base(company, filter, asOf, true);
        String order = filter.hasTime() ? "duration_ms DESC,updated_at DESC,id" : "updated_at DESC,id";
        return jdbc.sql(base.sql() + "SELECT * FROM filtered ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .params(base.parameters()).param("offset", offset).param("limit", limit)
                .query(JdbcWorkItemStatisticsRepository::item).list();
    }
    public long count(UUID company, StatisticsFilter filter, Instant asOf) {
        Query base = base(company, filter, asOf, true);
        return jdbc.sql(base.sql() + "SELECT COUNT(*) FROM filtered").params(base.parameters()).query(Long.class).single();
    }
    static Item item(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        var completed = rs.getTimestamp("completed_at");
        return new Item(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("item_no"),
                rs.getString("title"), rs.getObject("assignee_user_id", UUID.class), rs.getString("status_code"),
                rs.getString("status_name"), rs.getString("status_category"), rs.getString("status_color"), rs.getLong("duration_ms"),
                rs.getTimestamp("updated_at").toInstant(), rs.getObject("content_id", UUID.class), rs.getString("content_name"),
                rs.getString("priority"), rs.getString("priority_name"), rs.getObject("reporter_user_id", UUID.class),
                rs.getObject("due_date", java.time.LocalDate.class), rs.getObject("timeline_start_date", java.time.LocalDate.class),
                rs.getObject("timeline_end_date", java.time.LocalDate.class), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }
    record Query(String sql, Map<String, Object> parameters) {}
    static Query base(UUID company, StatisticsFilter f, Instant asOf, boolean withTime) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("company", company); p.put("projects", f.projectIds()); p.put("archived", f.includeArchived());
        p.put("asOf", Timestamp.from(asOf));
        String timing = withTime ? """
            timings AS (
              SELECT work_item_id, COUNT(*) AS session_count,
                SUM(GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(stopped_at,:asOf)-started_at))*1000))::bigint AS duration_ms
              FROM yumpoo.work_item_time_session
              WHERE company_id=:company AND project_id IN (:projects) AND deleted_at IS NULL GROUP BY work_item_id),
            """ : "";
        String timedColumns = withTime ? "COALESCE(t.duration_ms,0) AS duration_ms,COALESCE(t.session_count,0) AS session_count"
                : "0::bigint AS duration_ms,0::bigint AS session_count";
        String joinTime = withTime ? "LEFT JOIN timings t ON t.work_item_id=w.id " : "";
        String sql = "WITH " + timing + "base AS (SELECT w.*, " + timedColumns + """
            ,COALESCE(s.display_name,w.status_code) AS status_name,COALESCE(s.color_token,'GRAY') AS status_color,
            COALESCE(pr.display_name,w.priority,'未设置') AS priority_name,COALESCE(pr.color_token,'GRAY') AS priority_color,
            c.name AS content_name,c.color_token AS content_color
            FROM yumpoo.work_item w
            LEFT JOIN yumpoo.content c ON c.id=w.content_id AND c.company_id=w.company_id AND c.project_id=w.project_id
            LEFT JOIN yumpoo.project_work_item_status_label s
              ON s.company_id=w.company_id AND s.project_id=w.project_id AND s.status_code=w.status_code
            LEFT JOIN yumpoo.project_work_item_priority_label pr
              ON pr.company_id=w.company_id AND pr.project_id=w.project_id AND pr.priority_code=w.priority
            """ + joinTime + """
            WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL
              AND (:archived OR NOT w.archived)), filtered AS (SELECT * FROM base WHERE TRUE
            """;
        List<String> clauses = new ArrayList<>();
        if (!f.assignees().isEmpty()) {
            clauses.add("COALESCE(assignee_user_id::text,'UNASSIGNED') IN (:assignees)"); p.put("assignees", f.assignees());
        }
        if (!f.statuses().isEmpty()) {
            clauses.add("project_id::text||':'||status_code IN (:statuses)"); p.put("statuses", f.statuses());
        }
        if (!f.priorities().isEmpty()) {
            clauses.add("project_id::text||':'||COALESCE(priority,'UNASSIGNED') IN (:priorities)"); p.put("priorities", f.priorities());
        }
        if (!f.contentIds().isEmpty()) { clauses.add("content_id IN (:contents)"); p.put("contents", f.contentIds()); }
        if (!f.categories().isEmpty()) { clauses.add("status_category IN (:categories)"); p.put("categories", f.categories()); }
        if (f.dueFrom() != null) { clauses.add("due_date>=:dueFrom"); p.put("dueFrom", f.dueFrom()); }
        if (f.dueTo() != null) { clauses.add("due_date<=:dueTo"); p.put("dueTo", f.dueTo()); }
        if (f.hasTime()) clauses.add("session_count>0");
        if (!f.query().isEmpty()) {
            clauses.add("(title ILIKE :query ESCAPE '\\' OR item_no ILIKE :query ESCAPE '\\')");
            p.put("query", "%" + f.query().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return new Query(sql + (clauses.isEmpty() ? "" : " AND " + String.join(" AND ", clauses)) + ") ", p);
    }
}
