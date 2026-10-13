package com.yumpoo.platform.reporting.infrastructure;

import com.yumpoo.platform.reporting.application.WorkHoursAlertRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkHoursAlertRepository implements WorkHoursAlertRepository {
    private final JdbcClient jdbc;
    public JdbcWorkHoursAlertRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Setting> setting(UUID companyId) {
        return jdbc.sql("SELECT daily_threshold_minutes, row_version FROM yumpoo.work_hours_alert_setting WHERE company_id = :companyId")
                .param("companyId", companyId).query((rs, index) -> new Setting(rs.getInt("daily_threshold_minutes"), rs.getLong("row_version"))).optional();
    }

    @Override
    public boolean saveSetting(UUID companyId, int dailyThresholdMinutes, long expectedVersion, UUID actorUserId, Instant now) {
        String sql = expectedVersion == 0 ? """
                INSERT INTO yumpoo.work_hours_alert_setting (company_id, daily_threshold_minutes, row_version, updated_by_user_id, updated_at)
                VALUES (:companyId, :minutes, 1, :actor, :now) ON CONFLICT (company_id) DO NOTHING
                """ : """
                UPDATE yumpoo.work_hours_alert_setting SET daily_threshold_minutes = :minutes, row_version = row_version + 1,
                    updated_by_user_id = :actor, updated_at = :now
                WHERE company_id = :companyId AND row_version = :expected
                """;
        var statement = jdbc.sql(sql).param("companyId", companyId).param("minutes", dailyThresholdMinutes)
                .param("actor", actorUserId).param("now", utc(now));
        if (expectedVersion > 0) statement = statement.param("expected", expectedVersion);
        return statement.update() == 1;
    }

    @Override
    public Optional<UUID> recordAlert(UUID companyId, UUID userId, LocalDate workDate, long totalDurationMs, int thresholdMinutes, Instant detectedAt) {
        return jdbc.sql("""
                INSERT INTO yumpoo.work_hours_alert (id, company_id, user_id, work_date, total_duration_ms, threshold_minutes, detected_at)
                VALUES (:id, :companyId, :userId, :day, :total, :threshold, :detectedAt)
                ON CONFLICT (company_id, user_id, work_date) DO NOTHING RETURNING id
                """).param("id", UUID.randomUUID()).param("companyId", companyId).param("userId", userId)
                .param("day", workDate).param("total", totalDurationMs).param("threshold", thresholdMinutes)
                .param("detectedAt", utc(detectedAt)).query(UUID.class).optional();
    }

    private static OffsetDateTime utc(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
}
