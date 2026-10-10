package com.yumpoo.platform.catalog.infrastructure.project;

import com.yumpoo.platform.catalog.application.project.ProjectPurgeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProjectPurgeRepository implements ProjectPurgeRepository {
    private final JdbcClient jdbc;
    public JdbcProjectPurgeRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    public Optional<Reminder> remindOne(Instant now,Duration lead) {
        return jdbc.sql("""
                UPDATE yumpoo.project SET deletion_reminder_sent_at=:now
                WHERE id=(SELECT id FROM yumpoo.project WHERE deletion_requested_at IS NOT NULL
                    AND purge_started_at IS NULL AND deletion_reminder_sent_at IS NULL
                    AND purge_after<=:due ORDER BY purge_after,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING company_id,id,row_version,purge_after
                """).param("now",utc(now)).param("due",utc(now.plus(lead))).query((rs,n)->new Reminder(
                    rs.getObject("company_id",UUID.class),rs.getObject("id",UUID.class),rs.getLong("row_version"),
                    rs.getTimestamp("purge_after").toInstant())).optional();
    }
    public Optional<Lease> claim(Instant now,String worker,Duration duration,boolean newFirst) {
        if(newFirst) {
            var first=claimNew(now,worker,duration);
            if(first.isPresent()) return first;
        }
        UUID token=UUID.randomUUID();
        var resumed=jdbc.sql("""
                UPDATE yumpoo.project_purge_run SET lease_owner=:worker,lease_token=:token,lease_until=:until,updated_at=:now
                WHERE project_id=(SELECT project_id FROM yumpoo.project_purge_run
                    WHERE completed_at IS NULL AND (lease_until IS NULL OR lease_until<=:now)
                    ORDER BY updated_at,project_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING company_id,project_id,stage,lease_token,lease_until
                """).param("worker",worker).param("token",token).param("until",utc(now.plus(duration))).param("now",utc(now))
                .query(JdbcProjectPurgeRepository::lease).optional();
        if(resumed.isPresent()) return resumed;
        return claimNew(now,worker,duration);
    }
    private Optional<Lease> claimNew(Instant now,String worker,Duration duration) {
        UUID token=UUID.randomUUID();
        var project=jdbc.sql("""
                UPDATE yumpoo.project SET purge_started_at=:now,row_version=row_version+1
                WHERE id=(SELECT id FROM yumpoo.project WHERE purge_after<=:now AND purge_started_at IS NULL
                    ORDER BY purge_after,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING company_id,id
                """).param("now",utc(now)).query((rs,n)->new UUID[]{rs.getObject("company_id",UUID.class),rs.getObject("id",UUID.class)}).optional();
        if(project.isEmpty()) return Optional.empty();
        return jdbc.sql("""
                INSERT INTO yumpoo.project_purge_run(company_id,project_id,stage,lease_owner,lease_token,lease_until,started_at,updated_at)
                VALUES (:company,:project,'NOTIFICATION',:worker,:token,:until,:now,:now)
                RETURNING company_id,project_id,stage,lease_token,lease_until
                """).param("company",project.get()[0]).param("project",project.get()[1]).param("worker",worker)
                .param("token",token).param("until",utc(now.plus(duration))).param("now",utc(now))
                .query(JdbcProjectPurgeRepository::lease).optional();
    }
    public boolean hold(Lease lease,Instant now) {
        return owned("""
                SELECT project_id FROM yumpoo.project_purge_run WHERE company_id=:company AND project_id=:project
                    AND stage=:stage AND lease_token=:token AND lease_until>:now AND completed_at IS NULL FOR UPDATE
                """,lease,now).query(UUID.class).optional().isPresent();
    }
    public boolean advance(Lease lease,String nextStage,Instant now) {
        return owned("""
                UPDATE yumpoo.project_purge_run SET stage=:next,lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                    updated_at=:now,counts=jsonb_set(counts,ARRAY[stage],to_jsonb(COALESCE((counts->>stage)::bigint,0)+1))
                WHERE company_id=:company AND project_id=:project AND stage=:stage AND lease_token=:token
                    AND lease_until>:now AND completed_at IS NULL
                """,lease,now).param("next",nextStage).update()==1;
    }
    public boolean release(Lease lease,Instant now) {
        return owned("""
                UPDATE yumpoo.project_purge_run SET lease_owner=NULL,lease_token=NULL,lease_until=NULL,updated_at=:now,
                    counts=jsonb_set(counts,ARRAY[stage],to_jsonb(COALESCE((counts->>stage)::bigint,0)+1))
                WHERE company_id=:company AND project_id=:project AND stage=:stage AND lease_token=:token
                    AND lease_until>:now AND completed_at IS NULL
                """,lease,now).update()==1;
    }
    public Optional<Long> complete(Lease lease,Instant now) {
        var owned=owned("""
                SELECT project_id FROM yumpoo.project_purge_run WHERE company_id=:company AND project_id=:project
                    AND stage=:stage AND stage='CATALOG' AND lease_token=:token AND lease_until>:now
                    AND completed_at IS NULL FOR UPDATE
                """,lease,now).query(UUID.class).optional();
        if(owned.isEmpty()) return Optional.empty();
        var version=jdbc.sql("""
                DELETE FROM yumpoo.project WHERE company_id=:company AND id=:project AND purge_started_at IS NOT NULL
                RETURNING row_version
                """).param("company",lease.companyId()).param("project",lease.projectId()).query(Long.class).optional();
        if(version.isEmpty()) throw new IllegalStateException("purge run has no marked project");
        jdbc.sql("""
                UPDATE yumpoo.project_purge_run SET stage='COMPLETED',completed_at=:now,updated_at=:now,
                    cursor_value=NULL,counts='{}'::jsonb,lease_owner=NULL,lease_token=NULL,lease_until=NULL
                WHERE company_id=:company AND project_id=:project
                """).param("now",utc(now)).param("company",lease.companyId()).param("project",lease.projectId()).update();
        return version;
    }
    private JdbcClient.StatementSpec owned(String sql,Lease lease,Instant now) {
        return jdbc.sql(sql).param("company",lease.companyId()).param("project",lease.projectId())
                .param("stage",lease.stage()).param("token",lease.token()).param("now",utc(now));
    }
    private static java.time.OffsetDateTime utc(Instant value) { return value.atOffset(java.time.ZoneOffset.UTC); }
    private static Lease lease(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Lease(rs.getObject("company_id",UUID.class),rs.getObject("project_id",UUID.class),rs.getString("stage"),
                rs.getObject("lease_token",UUID.class),rs.getTimestamp("lease_until").toInstant());
    }
}
