package com.yumpoo.platform.foundation.infrastructure.scheduling;

import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobOutcome;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobRepository;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcScheduledJobRepository implements ScheduledJobRepository {
    private final JdbcClient jdbc;

    public JdbcScheduledJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> findAll(UUID companyId) {
        return jdbc.sql("SELECT * FROM yumpoo.scheduled_job WHERE company_id = :companyId")
                .param("companyId", companyId).query(this::map).list();
    }

    @Override
    public List<Row> findDue(Instant now) {
        return jdbc.sql("SELECT * FROM yumpoo.scheduled_job WHERE enabled AND next_run_at <= :now ORDER BY next_run_at")
                .param("now", utc(now)).query(this::map).list();
    }

    @Override
    public boolean save(UUID companyId, String jobCode, boolean enabled, String cron, ZoneId zoneId,
            Instant nextRunAt, UUID actorUserId, Instant now, long expectedVersion) {
        String sql = expectedVersion == 0 ? """
                INSERT INTO yumpoo.scheduled_job (company_id, job_code, enabled, cron, zone_id, next_run_at,
                    updated_by_user_id, updated_at, row_version)
                VALUES (:companyId, :jobCode, :enabled, :cron, :zoneId, :nextRunAt, :actorUserId, :now, 1)
                ON CONFLICT (company_id, job_code) DO NOTHING
                """ : """
                UPDATE yumpoo.scheduled_job SET enabled = :enabled, cron = :cron, zone_id = :zoneId,
                    next_run_at = :nextRunAt, updated_by_user_id = :actorUserId, updated_at = :now,
                    row_version = row_version + 1
                WHERE company_id = :companyId AND job_code = :jobCode AND row_version = :expected
                """;
        var statement = jdbc.sql(sql).param("companyId", companyId).param("jobCode", jobCode)
                .param("enabled", enabled).param("cron", cron).param("zoneId", zoneId.getId())
                .param("nextRunAt", utc(nextRunAt)).param("actorUserId", actorUserId).param("now", utc(now));
        if (expectedVersion > 0) statement = statement.param("expected", expectedVersion);
        return statement.update() == 1;
    }

    @Override
    public boolean claim(UUID companyId, String jobCode, Instant expectedNextRunAt, Instant followingRunAt,
            Instant startedAt, String requestId) {
        return jdbc.sql("""
                UPDATE yumpoo.scheduled_job SET next_run_at = :following, last_started_at = :startedAt,
                    last_request_id = :requestId
                WHERE company_id = :companyId AND job_code = :jobCode AND enabled AND next_run_at = :expected
                """).param("companyId", companyId).param("jobCode", jobCode)
                .param("following", utc(followingRunAt)).param("startedAt", utc(startedAt))
                .param("requestId", requestId).param("expected", utc(expectedNextRunAt)).update() == 1;
    }

    @Override
    public void finish(UUID companyId, String jobCode, Instant finishedAt, ScheduledJobResult result) {
        jdbc.sql("""
                UPDATE yumpoo.scheduled_job SET last_finished_at = :finishedAt, last_outcome = :outcome,
                    last_error_code = :errorCode
                WHERE company_id = :companyId AND job_code = :jobCode
                """).param("companyId", companyId).param("jobCode", jobCode).param("finishedAt", utc(finishedAt))
                .param("outcome", result.outcome().name()).param("errorCode", result.errorCode()).update();
    }

    private Row map(ResultSet rs, int index) throws SQLException {
        String outcome = rs.getString("last_outcome");
        return new Row(rs.getObject("company_id", UUID.class), rs.getString("job_code"), rs.getBoolean("enabled"),
                rs.getString("cron"), ZoneId.of(rs.getString("zone_id")), instant(rs, "next_run_at"),
                instant(rs, "last_started_at"), instant(rs, "last_finished_at"),
                outcome == null ? null : ScheduledJobOutcome.valueOf(outcome),
                rs.getString("last_error_code"), rs.getString("last_request_id"), rs.getLong("row_version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
