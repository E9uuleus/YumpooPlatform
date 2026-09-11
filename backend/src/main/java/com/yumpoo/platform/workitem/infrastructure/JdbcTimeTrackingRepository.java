package com.yumpoo.platform.workitem.infrastructure;

import com.yumpoo.platform.workitem.application.TimeTrackingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.yumpoo.platform.workitem.application.TimeTrackingModels.*;

@Repository
public class JdbcTimeTrackingRepository implements TimeTrackingRepository {
    private final JdbcClient jdbc;
    public JdbcTimeTrackingRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<TimerCandidate> candidates(UUID companyId, UUID userId, Collection<UUID> projectIds,
            Collection<UUID> matchingProjectIds, String query, boolean personal, int offset, int limit, Instant now) {
        if(projectIds.isEmpty()) return List.of();
        String projectMatch=matchingProjectIds.isEmpty() ? "" : " OR w.project_id IN (:matchingProjects)";
        var statement=jdbc.sql("""
            WITH own AS (
              SELECT work_item_id, MAX(started_at) FILTER (WHERE source='TIMER') AS last_tracked_at,
                SUM(GREATEST(0, EXTRACT(EPOCH FROM (COALESCE(stopped_at,:now)-started_at))*1000))::bigint AS duration
              FROM yumpoo.work_item_time_session
              WHERE company_id=:company AND user_id=:user AND deleted_at IS NULL AND project_id IN (:projects)
              GROUP BY work_item_id)
            SELECT w.id, w.project_id, w.item_no, w.title, c.name AS content_name, c.code AS content_code,
              c.color_token AS content_color_token, w.status_category,
              COALESCE(w.assignee_user_id=:user,false) AS assigned, own.last_tracked_at, COALESCE(own.duration,0) AS duration
            FROM yumpoo.work_item w JOIN yumpoo.content c ON c.id=w.content_id AND c.company_id=w.company_id
            LEFT JOIN own ON own.work_item_id=w.id
            WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL
              AND (:personal=false OR w.assignee_user_id=:user OR own.last_tracked_at IS NOT NULL)
              AND (:empty OR lower(w.title) LIKE :query ESCAPE '\\' OR lower(w.item_no) LIKE :query ESCAPE '\\'
            """ + projectMatch + """
              )
            ORDER BY own.last_tracked_at DESC NULLS LAST, assigned DESC, w.updated_at DESC, w.id
            LIMIT :limit OFFSET :offset
            """).param("company",companyId).param("user",userId).param("projects",projectIds)
                .param("personal",personal).param("empty",query.isEmpty())
                .param("query","%"+query.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")
                .param("now",Timestamp.from(now)).param("limit",limit).param("offset",offset);
        if(!matchingProjectIds.isEmpty()) statement.param("matchingProjects",matchingProjectIds);
        return statement.query((rs,n) -> new TimerCandidate(rs.getObject("id",UUID.class),
                rs.getObject("project_id",UUID.class), "", rs.getString("item_no"), rs.getString("title"),
                rs.getString("content_name"), rs.getString("content_code"), rs.getString("content_color_token"),
                rs.getString("status_category"), rs.getBoolean("assigned"),
                instant(rs,"last_tracked_at"), rs.getLong("duration"))).list();
    }

    public List<RecentTimeTrackingItem> recentItems(UUID companyId, UUID userId) {
        return jdbc.sql("""
            WITH recent AS (SELECT work_item_id,project_id,started_at FROM yumpoo.work_item_time_session
              WHERE company_id=:c AND user_id=:u AND deleted_at IS NULL AND source='TIMER'
              ORDER BY started_at DESC LIMIT 100)
            SELECT r.work_item_id,r.project_id,w.title FROM recent r
            JOIN yumpoo.work_item w ON w.id=r.work_item_id AND w.company_id=:c AND w.deleted_at IS NULL
            GROUP BY r.work_item_id,r.project_id,w.title ORDER BY MAX(r.started_at) DESC LIMIT 30
            """).param("c",companyId).param("u",userId)
                .query((rs,n) -> new RecentTimeTrackingItem(rs.getObject("work_item_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getString("title"))).list();
    }
    public List<UUID> visibleItemIds(UUID companyId, UUID projectId, Collection<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM yumpoo.work_item WHERE company_id=:c AND project_id=:p AND deleted_at IS NULL AND id IN (:ids)")
                .param("c",companyId).param("p",projectId).param("ids",ids).query(UUID.class).list();
    }
    public long stateVersion(UUID companyId, UUID userId, boolean lock) {
        if (lock) jdbc.sql("INSERT INTO yumpoo.work_item_timer_state(company_id,user_id) VALUES (:c,:u) ON CONFLICT DO NOTHING")
                .param("c", companyId).param("u", userId).update();
        return jdbc.sql("SELECT row_version FROM yumpoo.work_item_timer_state WHERE company_id=:c AND user_id=:u" + (lock ? " FOR UPDATE" : ""))
                .param("c", companyId).param("u", userId).query(Long.class).optional().orElse(0L);
    }
    public void advanceState(UUID companyId, UUID userId) {
        jdbc.sql("UPDATE yumpoo.work_item_timer_state SET row_version=row_version+1 WHERE company_id=:c AND user_id=:u")
                .param("c", companyId).param("u", userId).update();
    }
    public Optional<Session> running(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND user_id=:u AND stopped_at IS NULL AND deleted_at IS NULL")
                .param("c", companyId).param("u", userId).query(JdbcTimeTrackingRepository::map).optional();
    }
    public Optional<Session> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND id=:id")
                .param("c", companyId).param("id", id).query(JdbcTimeTrackingRepository::map).optional();
    }
    public List<Session> history(UUID companyId, UUID itemId, Instant before, UUID beforeId, int limit) {
        var query = jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND work_item_id=:w AND deleted_at IS NULL"
                + (before == null ? "" : " AND (started_at,id)<(:before,:beforeId)") + " ORDER BY started_at DESC,id DESC LIMIT :limit")
                .param("c", companyId).param("w", itemId).param("limit", limit);
        if (before != null) query.param("before", Timestamp.from(before)).param("beforeId", beforeId);
        return query.query(JdbcTimeTrackingRepository::map).list();
    }
    public boolean overlaps(UUID companyId, UUID userId, Instant start, Instant stop, UUID excludedId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo.work_item_time_session WHERE company_id=:c AND user_id=:u AND deleted_at IS NULL AND id<>:excluded AND started_at<:stop AND (stopped_at IS NULL OR stopped_at>:start))")
                .param("c", companyId).param("u", userId).param("excluded", excludedId)
                .param("start", Timestamp.from(start)).param("stop", Timestamp.from(stop)).query(Boolean.class).single();
    }
    public void save(Session s) {
        jdbc.sql("""
            INSERT INTO yumpoo.work_item_time_session(id,company_id,project_id,work_item_id,user_id,started_at,stopped_at,source,row_version,deleted_at,change_reason)
            VALUES (:id,:c,:p,:w,:u,:start,:stop,:source,:v,:deleted,:reason)
            ON CONFLICT (id) DO UPDATE SET started_at=EXCLUDED.started_at,stopped_at=EXCLUDED.stopped_at,
                row_version=EXCLUDED.row_version,deleted_at=EXCLUDED.deleted_at,change_reason=EXCLUDED.change_reason
            """).param("id", s.id()).param("c", s.companyId()).param("p", s.projectId()).param("w", s.workItemId())
                .param("u", s.userId()).param("start", Timestamp.from(s.startedAt())).param("stop", timestamp(s.stoppedAt()))
                .param("source", s.source()).param("v", s.rowVersion()).param("deleted", timestamp(s.deletedAt()))
                .param("reason", s.changeReason()).update();
    }
    public void advanceProjects(UUID companyId, Collection<UUID> ids) {
        ids.stream().distinct().sorted().forEach(id -> jdbc.sql("INSERT INTO yumpoo.work_item_time_revision(company_id,project_id,revision) VALUES (:c,:p,1) ON CONFLICT(company_id,project_id) DO UPDATE SET revision=work_item_time_revision.revision+1")
                .param("c", companyId).param("p", id).update());
    }
    public long revision(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT revision FROM yumpoo.work_item_time_revision WHERE company_id=:c AND project_id=:p")
                .param("c", companyId).param("p", projectId).query(Long.class).optional().orElse(0L);
    }
    public List<TimeTrackingSummary> summaries(UUID companyId, UUID projectId, UUID userId, Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) return List.of();
        List<Session> running = jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND project_id=:p AND work_item_id IN (:ids) AND deleted_at IS NULL AND stopped_at IS NULL")
                .param("c", companyId).param("p", projectId).param("ids", ids).query(JdbcTimeTrackingRepository::map).list();
        Map<UUID, long[]> totals = new HashMap<>();
        jdbc.sql("""
            SELECT work_item_id,COUNT(*) AS count,
              COALESCE(SUM(EXTRACT(EPOCH FROM (stopped_at-started_at))*1000) FILTER (WHERE stopped_at IS NOT NULL),0)::bigint AS completed,
              COALESCE(SUM(GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(stopped_at,:now)-started_at))*1000)),0)::bigint AS total,
              COALESCE(SUM(GREATEST(0,EXTRACT(EPOCH FROM (COALESCE(stopped_at,:now)-started_at))*1000)) FILTER (WHERE user_id=:u),0)::bigint AS own
            FROM yumpoo.work_item_time_session WHERE company_id=:c AND project_id=:p AND work_item_id IN (:ids) AND deleted_at IS NULL GROUP BY work_item_id
            """).param("c", companyId).param("p", projectId).param("ids", ids).param("u", userId).param("now", Timestamp.from(now))
                .query((rs, n) -> { totals.put(rs.getObject("work_item_id", UUID.class), new long[]{rs.getLong("completed"),rs.getLong("total"),rs.getLong("own"),rs.getLong("count")}); return 0; }).list();
        return ids.stream().distinct().map(id -> {
            long[] t = totals.getOrDefault(id, new long[4]);
            return new TimeTrackingSummary(id,t[0],t[1],t[2],t[3],running.stream().filter(s -> s.workItemId().equals(id)).map(s -> new RunningSession(s.userId(),s.startedAt())).toList());
        }).toList();
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String key) throws SQLException { Timestamp t=rs.getTimestamp(key); return t==null ? null : t.toInstant(); }
    private static Session map(ResultSet rs, int n) throws SQLException {
        return new Session(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("work_item_id",UUID.class),rs.getObject("user_id",UUID.class),instant(rs,"started_at"),instant(rs,"stopped_at"),rs.getString("source"),rs.getLong("row_version"),instant(rs,"deleted_at"),rs.getString("change_reason"));
    }
}
