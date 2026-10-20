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
            WITH last_tracked AS (
              SELECT work_item_id, MAX(started_at) AS last_tracked_at
              FROM yumpoo.work_item_time_session
              WHERE company_id=:company AND user_id=:user AND deleted_at IS NULL AND project_id IN (:projects)
              GROUP BY work_item_id), allocated AS (
              SELECT sh.work_item_id, FLOOR(COALESCE(SUM(%s),0))::bigint AS duration
              FROM yumpoo.work_item_time_share sh
              WHERE sh.company_id=:company AND sh.user_id=:user AND sh.project_id IN (:projects)
              GROUP BY sh.work_item_id), own AS (
              SELECT t.work_item_id,t.last_tracked_at,COALESCE(a.duration,0) AS duration
              FROM last_tracked t LEFT JOIN allocated a ON a.work_item_id=t.work_item_id)
            SELECT w.id, w.project_id, w.item_no, w.title, c.name AS content_name, c.code AS content_code,
              c.color_token AS content_color_token, w.status_category,
              COALESCE(s.completed_status_codes @> jsonb_build_array(w.status_code), w.status_category IN ('DONE','CANCELED')) AS completed,
              EXISTS (SELECT 1 FROM yumpoo.work_item_assignee a WHERE a.work_item_id=w.id AND a.user_id=:user) AS assigned, own.last_tracked_at, COALESCE(own.duration,0) AS duration
            FROM yumpoo.work_item w JOIN yumpoo.content c ON c.id=w.content_id AND c.company_id=w.company_id
            LEFT JOIN own ON own.work_item_id=w.id
            LEFT JOIN yumpoo.work_item_table_settings s ON s.company_id=w.company_id AND s.project_id=w.project_id AND s.user_id=:user
            WHERE w.company_id=:company AND w.project_id IN (:projects) AND w.deleted_at IS NULL AND NOT w.archived
              AND (:personal=false OR EXISTS (SELECT 1 FROM yumpoo.work_item_assignee a WHERE a.work_item_id=w.id AND a.user_id=:user) OR own.last_tracked_at IS NOT NULL)
              AND (:empty OR lower(w.title) LIKE :query ESCAPE '\\' OR lower(w.item_no) LIKE :query ESCAPE '\\'
            """.formatted(TimeShareSql.allocated("sh",":now")) + projectMatch + """
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
                rs.getString("status_category"), rs.getBoolean("completed"), rs.getBoolean("assigned"),
                instant(rs,"last_tracked_at"), rs.getLong("duration"))).list();
    }

    public List<RecentTimeTrackingItem> recentItems(UUID companyId, UUID userId) {
        return jdbc.sql("""
            WITH recent AS (SELECT work_item_id,project_id,started_at FROM yumpoo.work_item_time_session
              WHERE company_id=:c AND user_id=:u AND deleted_at IS NULL AND source='TIMER'
              ORDER BY started_at DESC LIMIT 100)
            SELECT r.work_item_id,r.project_id,w.title FROM recent r
            JOIN yumpoo.work_item w ON w.id=r.work_item_id AND w.company_id=:c AND w.deleted_at IS NULL AND NOT w.archived
            GROUP BY r.work_item_id,r.project_id,w.title ORDER BY MAX(r.started_at) DESC LIMIT 30
            """).param("c",companyId).param("u",userId)
                .query((rs,n) -> new RecentTimeTrackingItem(rs.getObject("work_item_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getString("title"))).list();
    }
    public List<UUID> visibleItemIds(UUID companyId, UUID projectId, Collection<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id FROM yumpoo.work_item WHERE company_id=:c AND project_id=:p AND deleted_at IS NULL AND id IN (:ids)")
                .param("c",companyId).param("p",projectId).param("ids",ids).query(UUID.class).list();
    }
    public List<RecentTimeTrackingItem> resumeItems(UUID companyId, UUID userId) {
        return jdbc.sql("""
            SELECT s.work_item_id,s.project_id,w.title
            FROM yumpoo.work_item_time_session s JOIN yumpoo.work_item w
              ON w.id=s.work_item_id AND w.company_id=s.company_id AND w.deleted_at IS NULL AND NOT w.archived
            WHERE s.company_id=:c AND s.user_id=:u AND s.deleted_at IS NULL AND s.source='TIMER'
              AND s.stopped_at=(SELECT MAX(stopped_at) FROM yumpoo.work_item_time_session
                WHERE company_id=:c AND user_id=:u AND deleted_at IS NULL AND source='TIMER')
            GROUP BY s.work_item_id,s.project_id,w.title ORDER BY MAX(s.started_at) DESC,s.work_item_id
            """).param("c",companyId).param("u",userId)
                .query((rs,n) -> new RecentTimeTrackingItem(rs.getObject("work_item_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getString("title"))).list();
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
    public Optional<TimeTrackingPreferences> preferences(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT timer_enabled,workday_start_minute,workday_end_minute,hide_completed FROM yumpoo.work_item_timer_state WHERE company_id=:c AND user_id=:u")
                .param("c", companyId).param("u", userId)
                .query((rs,n) -> new TimeTrackingPreferences(rs.getBoolean("timer_enabled"),rs.getInt("workday_start_minute"),rs.getInt("workday_end_minute"), rs.getBoolean("hide_completed")))
                .optional();
    }
    public void savePreferences(UUID companyId, UUID userId, TimeTrackingPreferences p) {
        jdbc.sql("UPDATE yumpoo.work_item_timer_state SET timer_enabled=:enabled,workday_start_minute=:start,workday_end_minute=:end,hide_completed=:hide WHERE company_id=:c AND user_id=:u")
                .param("c", companyId).param("u", userId).param("enabled", p.timerEnabled())
                .param("start", p.workdayStartMinute()).param("end", p.workdayEndMinute()).param("hide", p.hideCompleted()).update();
    }
    public List<Session> running(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND user_id=:u AND stopped_at IS NULL AND deleted_at IS NULL ORDER BY started_at DESC,id DESC")
                .param("c", companyId).param("u", userId).query(JdbcTimeTrackingRepository::map).list();
    }
    public List<UUID> runningUserIds(UUID companyId, UUID projectId) {
        return jdbc.sql("SELECT DISTINCT user_id FROM yumpoo.work_item_time_session WHERE company_id=:c AND project_id=:p AND stopped_at IS NULL AND deleted_at IS NULL ORDER BY user_id")
                .param("c", companyId).param("p", projectId).query(UUID.class).list();
    }
    public Optional<Session> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND id=:id")
                .param("c", companyId).param("id", id).query(JdbcTimeTrackingRepository::map).optional();
    }
    public List<Session> history(UUID companyId, UUID itemId, boolean includeChildren, Instant before, UUID beforeId, int limit) {
        String scope=includeChildren ? "(work_item_id=:w OR work_item_id IN (SELECT r.right_work_item_id FROM yumpoo.work_item_relation r JOIN yumpoo.work_item child ON child.company_id=r.company_id AND child.id=r.right_work_item_id AND child.deleted_at IS NULL WHERE r.company_id=:c AND r.left_work_item_id=:w AND r.relation_type='PARENT_CHILD' AND r.deleted_at IS NULL))" : "work_item_id=:w";
        var query = jdbc.sql("SELECT * FROM yumpoo.work_item_time_session WHERE company_id=:c AND "+scope+" AND deleted_at IS NULL"
                + (before == null ? "" : " AND (started_at,id)<(:before,:beforeId)") + " ORDER BY started_at DESC,id DESC LIMIT :limit")
                .param("c", companyId).param("w", itemId).param("limit", limit);
        if (before != null) query.param("before", Timestamp.from(before)).param("beforeId", beforeId);
        return query.query(JdbcTimeTrackingRepository::map).list();
    }
    public boolean sameItemOverlaps(UUID companyId, UUID userId, UUID workItemId, Instant start, Instant stop, UUID excludedId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM yumpoo.work_item_time_session WHERE company_id=:c AND user_id=:u AND work_item_id=:w AND deleted_at IS NULL AND id<>:excluded AND started_at<:stop AND (stopped_at IS NULL OR stopped_at>:start))")
                .param("c", companyId).param("u", userId).param("w",workItemId).param("excluded", excludedId)
                .param("start", Timestamp.from(start)).param("stop", Timestamp.from(stop)).query(Boolean.class).single();
    }
    public List<MySessionRow> mySessions(UUID companyId,UUID userId,Instant from,Instant to) {
        return jdbc.sql("""
            SELECT s.*,w.item_no,w.title,w.archived,w.deleted_at AS item_deleted_at,c.color_token
            FROM yumpoo.work_item_time_session s
            JOIN yumpoo.work_item w ON w.company_id=s.company_id AND w.id=s.work_item_id
            JOIN yumpoo.content c ON c.company_id=w.company_id AND c.id=w.content_id
            WHERE s.company_id=:c AND s.user_id=:u AND s.deleted_at IS NULL
              AND s.started_at<:to AND (s.stopped_at IS NULL OR s.stopped_at>:from)
            ORDER BY s.started_at,s.id
            """).param("c",companyId).param("u",userId).param("from",Timestamp.from(from)).param("to",Timestamp.from(to))
                .query((rs,n) -> new MySessionRow(map(rs,n),rs.getString("item_no"),rs.getString("title"),
                        rs.getString("color_token"),rs.getBoolean("archived"),rs.getTimestamp("item_deleted_at")!=null)).list();
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
    public void touchProjects(UUID companyId, Collection<UUID> ids) {
        ids.stream().distinct().sorted().forEach(id -> jdbc.sql("UPDATE yumpoo.work_item_time_revision SET revision=revision+1 WHERE company_id=:c AND project_id=:p")
                .param("c",companyId).param("p",id).update());
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
        Map<UUID,Long> allocated=new HashMap<>();
        Map<UUID,Long> rollup=new HashMap<>();
        Map<UUID,Double> rates=new HashMap<>();
        jdbc.sql("""
            WITH scope AS (
              SELECT w.id AS root_id,w.id AS item_id FROM yumpoo.work_item w WHERE w.company_id=:c AND w.id IN (:ids)
              UNION ALL
              SELECT r.left_work_item_id,r.right_work_item_id FROM yumpoo.work_item_relation r
              JOIN yumpoo.work_item child ON child.company_id=r.company_id AND child.id=r.right_work_item_id AND child.deleted_at IS NULL
              WHERE r.company_id=:c AND r.relation_type='PARENT_CHILD' AND r.deleted_at IS NULL AND r.left_work_item_id IN (:ids))
            SELECT s.root_id,FLOOR(COALESCE(SUM(%s) FILTER (WHERE sh.work_item_id=s.root_id),0))::bigint AS allocated,
              FLOOR(COALESCE(SUM(%s),0))::bigint AS rollup,
              COALESCE(SUM(1.0/sh.concurrency) FILTER (WHERE sh.stopped_at IS NULL),0) AS rate
            FROM scope s JOIN yumpoo.work_item_time_share sh ON sh.company_id=:c AND sh.work_item_id=s.item_id
            GROUP BY s.root_id
            """.formatted(TimeShareSql.allocated("sh",":now"),TimeShareSql.allocated("sh",":now"))).param("c",companyId).param("ids",ids).param("now",Timestamp.from(now))
                .query((rs,n) -> { UUID id=rs.getObject("root_id",UUID.class); allocated.put(id,rs.getLong("allocated")); rollup.put(id,rs.getLong("rollup")); rates.put(id,rs.getDouble("rate")); return 0; }).list();
        return ids.stream().distinct().map(id -> {
            long[] t = totals.getOrDefault(id, new long[4]);
            return new TimeTrackingSummary(id,t[0],t[1],t[2],t[3],running.stream().filter(s -> s.workItemId().equals(id)).map(s -> new RunningSession(s.userId(),s.startedAt())).toList(),
                    allocated.getOrDefault(id,0L),rollup.getOrDefault(id,0L),rates.getOrDefault(id,0D));
        }).toList();
    }
    public Map<UUID,long[]> sessionShares(UUID companyId,Collection<UUID> ids,Instant now) {
        var shares=new HashMap<UUID,long[]>();
        if(ids.isEmpty()) return shares;
        jdbc.sql("SELECT a.session_id,FLOOR(SUM("+TimeShareSql.allocated("a",":now")+"))::bigint AS allocated FROM yumpoo.work_item_time_share a WHERE a.company_id=:c AND a.session_id IN (:ids) GROUP BY a.session_id")
                .param("c",companyId).param("ids",ids).param("now",Timestamp.from(now))
                .query((rs,n) -> { shares.put(rs.getObject("session_id",UUID.class),new long[]{rs.getLong("allocated"),0}); return 0; }).list();
        jdbc.sql("""
            SELECT a.session_id,COUNT(DISTINCT b.work_item_id) AS parallel
            FROM yumpoo.work_item_time_share a JOIN yumpoo.work_item_time_share b
              ON b.company_id=a.company_id AND b.user_id=a.user_id AND b.started_at=a.started_at AND b.work_item_id<>a.work_item_id
            WHERE a.company_id=:c AND a.session_id IN (:ids) GROUP BY a.session_id
            """).param("c",companyId).param("ids",ids)
                .query((rs,n) -> { shares.computeIfAbsent(rs.getObject("session_id",UUID.class),id -> new long[2])[1]=rs.getLong("parallel"); return 0; }).list();
        return shares;
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String key) throws SQLException { Timestamp t=rs.getTimestamp(key); return t==null ? null : t.toInstant(); }
    static Session map(ResultSet rs, int n) throws SQLException {
        return new Session(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getObject("work_item_id",UUID.class),rs.getObject("user_id",UUID.class),instant(rs,"started_at"),instant(rs,"stopped_at"),rs.getString("source"),rs.getLong("row_version"),instant(rs,"deleted_at"),rs.getString("change_reason"));
    }
}
