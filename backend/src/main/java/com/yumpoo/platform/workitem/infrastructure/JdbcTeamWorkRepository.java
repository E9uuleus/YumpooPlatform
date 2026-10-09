package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.TeamWorkRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcTeamWorkRepository implements TeamWorkRepository {
    private static final String OPEN_WORK = """
        FROM yumpoo.work_item w
        WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL AND NOT w.archived
          AND w.status_category IN ('TODO','IN_PROGRESS')
        """;
    private static final String ASSIGNED = " AND EXISTS (SELECT 1 FROM yumpoo.work_item_assignee a WHERE a.company_id=w.company_id AND a.work_item_id=w.id AND a.user_id=:user)";
    private static final String UNASSIGNED = " AND NOT EXISTS (SELECT 1 FROM yumpoo.work_item_assignee a WHERE a.company_id=w.company_id AND a.work_item_id=w.id)";
    private final JdbcClient jdbc;
    public JdbcTeamWorkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Each session contributes its overlap with every company-calendar day it touches; running sessions end at asOf. */
    public List<TimeEntry> timeEntries(UUID companyId, List<UUID> projectIds, List<UUID> userIds, LocalDate from, LocalDate to, ZoneId zone, Instant asOf) {
        String overlap = "EXTRACT(EPOCH FROM (LEAST(x.ended_at,days.day_end)-GREATEST(x.started_at,days.day_start)))*1000";
        var statement = jdbc.sql("""
            WITH days AS (
              SELECT d::date AS day, d AT TIME ZONE CAST(:zone AS text) AS day_start,
                     (d + interval '1 day') AT TIME ZONE CAST(:zone AS text) AS day_end
              FROM generate_series(CAST(:from AS date)::timestamp, CAST(:to AS date)::timestamp, interval '1 day') AS d),
            sessions AS (
              SELECT s.user_id,s.work_item_id,s.started_at,COALESCE(s.stopped_at,CAST(:asOf AS timestamptz)) AS ended_at
              FROM yumpoo.work_item_time_session s
              WHERE s.company_id=:company AND s.deleted_at IS NULL AND s.project_id IN (:projects)
                AND s.started_at < CAST(:rangeEnd AS timestamptz)
                AND COALESCE(s.stopped_at,CAST(:asOf AS timestamptz)) > CAST(:rangeStart AS timestamptz)
            """ + (userIds.isEmpty() ? "" : " AND s.user_id IN (:users)") + """
            )
            SELECT x.user_id,x.work_item_id,w.project_id,w.item_no,w.title,days.day,SUM(%1$s)::bigint AS duration_ms
            FROM sessions x
            JOIN days ON x.started_at < days.day_end AND x.ended_at > days.day_start
            JOIN yumpoo.work_item w ON w.id=x.work_item_id AND w.company_id=:company AND w.deleted_at IS NULL
            GROUP BY x.user_id,x.work_item_id,w.project_id,w.item_no,w.title,days.day
            HAVING SUM(%1$s) >= 1
            ORDER BY days.day,x.user_id,w.item_no
            """.formatted(overlap))
                .param("company", companyId).param("projects", projectIds).param("zone", zone.getId())
                .param("from", from).param("to", to).param("asOf", Timestamp.from(asOf))
                .param("rangeStart", Timestamp.from(from.atStartOfDay(zone).toInstant()))
                .param("rangeEnd", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        if (!userIds.isEmpty()) statement = statement.param("users", userIds);
        return statement.query((rs, n) -> new TimeEntry(rs.getObject("user_id", UUID.class), rs.getObject("work_item_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("item_no"), rs.getString("title"),
                rs.getObject("day", LocalDate.class), rs.getLong("duration_ms"))).list();
    }

    public List<Load> currentLoad(UUID companyId, List<UUID> projectIds, LocalDate today) {
        return jdbc.sql("""
            SELECT a.user_id,
              COUNT(*) FILTER (WHERE w.status_category='TODO') AS todo,
              COUNT(*) FILTER (WHERE w.status_category='IN_PROGRESS') AS in_progress,
              COUNT(*) FILTER (WHERE w.due_date < CAST(:today AS date)) AS overdue
            FROM yumpoo.work_item w
            LEFT JOIN yumpoo.work_item_assignee a ON a.company_id=w.company_id AND a.work_item_id=w.id
            WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL AND NOT w.archived
              AND w.status_category IN ('TODO','IN_PROGRESS')
            GROUP BY a.user_id
            """).param("company", companyId).param("projects", projectIds).param("today", today)
                .query((rs, n) -> new Load(rs.getObject("user_id", UUID.class), rs.getLong("todo"), rs.getLong("in_progress"), rs.getLong("overdue"))).list();
    }

    public List<Task> currentTasks(UUID companyId, List<UUID> projectIds, UUID userId, LocalDate today, int offset, int limit) {
        var statement = jdbc.sql("""
            SELECT w.id,w.project_id,w.item_no,w.title,COALESCE(s.display_name,w.status_code) AS status_name,w.status_category,
              COALESCE(s.color_token,'GRAY') AS status_color,pr.display_name AS priority_name,pr.color_token AS priority_color,w.due_date
            FROM yumpoo.work_item w
            LEFT JOIN yumpoo.project_work_item_status_label s
              ON s.company_id=w.company_id AND s.project_id=w.project_id AND s.status_code=w.status_code
            LEFT JOIN yumpoo.project_work_item_priority_label pr
              ON pr.company_id=w.company_id AND pr.project_id=w.project_id AND pr.priority_code=w.priority
            WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL AND NOT w.archived
              AND w.status_category IN ('TODO','IN_PROGRESS')
            """ + (userId == null ? UNASSIGNED : ASSIGNED) + """
             ORDER BY w.due_date ASC NULLS LAST, CASE w.status_category WHEN 'IN_PROGRESS' THEN 0 ELSE 1 END, w.updated_at DESC, w.id
             LIMIT :limit OFFSET :offset
            """).param("company", companyId).param("projects", projectIds).param("limit", limit).param("offset", offset);
        if (userId != null) statement = statement.param("user", userId);
        return statement.query((rs, n) -> {
            LocalDate due = rs.getObject("due_date", LocalDate.class);
            return new Task(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("item_no"), rs.getString("title"),
                    rs.getString("status_name"), rs.getString("status_category"), rs.getString("status_color"), rs.getString("priority_name"),
                    rs.getString("priority_color"), due, due != null && due.isBefore(today));
        }).list();
    }

    public long countCurrentTasks(UUID companyId, List<UUID> projectIds, UUID userId) {
        var statement = jdbc.sql("SELECT COUNT(*) " + OPEN_WORK + (userId == null ? UNASSIGNED : ASSIGNED))
                .param("company", companyId).param("projects", projectIds);
        if (userId != null) statement = statement.param("user", userId);
        return statement.query(Long.class).single();
    }
}
