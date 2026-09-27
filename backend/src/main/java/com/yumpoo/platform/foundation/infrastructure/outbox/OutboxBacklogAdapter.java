package com.yumpoo.platform.foundation.infrastructure.outbox;
import com.yumpoo.platform.foundation.application.outbox.OutboxBacklogPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;

@Repository
public class OutboxBacklogAdapter implements OutboxBacklogPort {
    private final JdbcTemplate jdbc;
    public OutboxBacklogAdapter(DataSource source) { jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(3); }
    @Override public Backlog read() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status='PENDING') AS pending,
                  count(*) FILTER (WHERE status='PROCESSING') AS processing,
                  count(*) FILTER (WHERE status='RETRY') AS retry,
                  count(*) FILTER (WHERE status='DEAD') AS dead,
                  min(occurred_at) FILTER (WHERE status<>'DEAD') AS oldest
                FROM yumpoo.outbox_event WHERE status IN ('PENDING','PROCESSING','RETRY','DEAD')
                """, (rs, n) -> new Backlog(rs.getLong("pending"), rs.getLong("processing"), rs.getLong("retry"), rs.getLong("dead"),
                rs.getTimestamp("oldest") == null ? null : rs.getTimestamp("oldest").toInstant()));
    }
}
