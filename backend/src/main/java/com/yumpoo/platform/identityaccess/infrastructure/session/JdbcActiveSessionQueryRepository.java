package com.yumpoo.platform.identityaccess.infrastructure.session;
import com.yumpoo.platform.identityaccess.application.session.ActiveSessionQueryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcActiveSessionQueryRepository implements ActiveSessionQueryRepository {
    private final JdbcTemplate jdbc;
    public JdbcActiveSessionQueryRepository(DataSource source) { jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(3); }
    @Override public List<ActiveSessionRecord> find(UUID companyId, Instant now) {
        return jdbc.query("""
                SELECT s.id,s.company_id,s.user_id,u.display_name,s.client_type,s.client_version,
                    s.issued_at,s.last_seen_at,least(s.idle_expires_at,s.absolute_expires_at) expires_at
                FROM yumpoo.login_session s JOIN yumpoo.identity_user u ON u.id=s.user_id AND u.company_id=s.company_id
                WHERE s.company_id=? AND s.status='ACTIVE' AND s.idle_expires_at>? AND s.absolute_expires_at>?
                  AND u.employment_status='ACTIVE' AND u.account_status='ENABLED'
                  AND s.issued_authorization_version=u.authorization_version
                ORDER BY s.last_seen_at DESC,s.id
                """, (rs, n) -> new ActiveSessionRecord(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("client_type"), rs.getString("client_version"),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                companyId, Timestamp.from(now), Timestamp.from(now));
    }
    @Override public ActiveSessionCounts count(Instant now) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE seen>=?) online,
                       count(*) FILTER (WHERE seen<? AND seen>=?) idle
                FROM (
                    SELECT max(s.last_seen_at) seen
                    FROM yumpoo.login_session s
                    JOIN yumpoo.identity_user u ON u.id=s.user_id AND u.company_id=s.company_id
                    WHERE s.status='ACTIVE' AND s.idle_expires_at>? AND s.absolute_expires_at>?
                      AND u.employment_status='ACTIVE' AND u.account_status='ENABLED'
                      AND s.issued_authorization_version=u.authorization_version
                    GROUP BY s.company_id,s.user_id
                ) members
                """, (rs, row) -> new ActiveSessionCounts(rs.getLong("online"), rs.getLong("idle")),
                Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(120)),
                Timestamp.from(now.minusSeconds(1800)), Timestamp.from(now), Timestamp.from(now));
    }
}
