package com.yumpoo.platform.organization.infrastructure;

import com.yumpoo.platform.organization.application.CompanyRepository;
import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import com.yumpoo.platform.organization.domain.Company;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcCompanyRepository implements CompanyRepository {

    private static final String SELECT_CURRENT_COMPANY = """
            SELECT
                id,
                display_name,
                timezone,
                week_start_day,
                default_workday_minutes,
                row_version
            FROM yumpoo.company
            ORDER BY singleton_slot
            LIMIT 2
            """;

    private static final String SELECT_CALENDAR_DAYS = """
            SELECT
                calendar_date,
                day_type,
                standard_minutes,
                source,
                note,
                row_version
            FROM yumpoo.company_calendar_day
            WHERE company_id = :companyId
              AND calendar_date BETWEEN :startInclusive AND :endInclusive
            ORDER BY calendar_date
            """;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate batchJdbc;

    public JdbcCompanyRepository(JdbcClient jdbcClient, NamedParameterJdbcTemplate batchJdbc) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.batchJdbc = batchJdbc;
    }

    @Override
    public Company current() {
        List<Company> records = jdbcClient.sql(SELECT_CURRENT_COMPANY)
                .query(JdbcCompanyRepository::mapCompany)
                .list();
        if (records.size() != 1) {
            throw new IllegalStateException("Exactly one Company configuration is required");
        }
        return records.getFirst();
    }

    @Override
    public boolean rename(UUID companyId, String displayName, long expectedVersion, Instant now) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.company SET display_name = :displayName, row_version = row_version + 1, updated_at = :now
                        WHERE id = :companyId AND row_version = :expected
                        """)
                .param("companyId", companyId).param("displayName", displayName).param("expected", expectedVersion)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update() == 1;
    }

    @Override
    public List<CalendarDayOverride> calendarDays(
            UUID companyId,
            LocalDate startInclusive,
            LocalDate endInclusive
    ) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        Objects.requireNonNull(endInclusive, "endInclusive must not be null");
        return jdbcClient.sql(SELECT_CALENDAR_DAYS)
                .param("companyId", companyId)
                .param("startInclusive", startInclusive)
                .param("endInclusive", endInclusive)
                .query(JdbcCompanyRepository::mapCalendarDay)
                .list();
    }

    @Override
    public long calendarYearVersion(UUID companyId, int year) {
        return jdbcClient.sql("SELECT row_version FROM yumpoo.company_calendar_year WHERE company_id = :companyId AND calendar_year = :year")
                .param("companyId", companyId).param("year", year).query(Long.class).optional().orElse(0L);
    }

    @Override
    public boolean advanceCalendarYear(UUID companyId, int year, long expectedVersion, UUID actorUserId, Instant now) {
        String sql = expectedVersion == 0 ? """
                INSERT INTO yumpoo.company_calendar_year (company_id, calendar_year, row_version, updated_by_user_id, updated_at)
                VALUES (:companyId, :year, 1, :actor, :now) ON CONFLICT (company_id, calendar_year) DO NOTHING
                """ : """
                UPDATE yumpoo.company_calendar_year SET row_version = row_version + 1, updated_by_user_id = :actor, updated_at = :now
                WHERE company_id = :companyId AND calendar_year = :year AND row_version = :expected
                """;
        var statement = jdbcClient.sql(sql).param("companyId", companyId).param("year", year)
                .param("actor", actorUserId).param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        if (expectedVersion > 0) statement.param("expected", expectedVersion);
        return statement.update() == 1;
    }

    @Override
    public void replaceCalendarDays(UUID companyId, int year, List<CalendarDayOverride> days, Instant now) {
        jdbcClient.sql("DELETE FROM yumpoo.company_calendar_day WHERE company_id = :companyId AND calendar_date BETWEEN :start AND :end")
                .param("companyId", companyId).param("start", LocalDate.of(year, 1, 1)).param("end", LocalDate.of(year, 12, 31)).update();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        if (!days.isEmpty()) {
            batchJdbc.batchUpdate("""
                    INSERT INTO yumpoo.company_calendar_day (company_id, calendar_date, day_type, standard_minutes, source, note, row_version, created_at, updated_at)
                    VALUES (:companyId, :date, :dayType, :minutes, :source, :note, 0, :now, :now)
                    """, days.stream().map(day -> new MapSqlParameterSource()
                    .addValue("companyId", companyId).addValue("date", day.date()).addValue("dayType", day.dayType().name())
                    .addValue("minutes", day.standardMinutes(), Types.INTEGER).addValue("source", day.source().name())
                    .addValue("note", day.note(), Types.VARCHAR).addValue("now", timestamp)).toArray(MapSqlParameterSource[]::new));
        }
    }

    private static Company mapCompany(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new Company(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("display_name"),
                    ZoneId.of(resultSet.getString("timezone")),
                    DayOfWeek.valueOf(resultSet.getString("week_start_day")),
                    resultSet.getInt("default_workday_minutes"),
                    resultSet.getLong("row_version")
            );
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored Company configuration is invalid", exception);
        }
    }

    private static CalendarDayOverride mapCalendarDay(ResultSet resultSet, int rowNumber) throws SQLException {
        Integer standardMinutes = resultSet.getObject("standard_minutes", Integer.class);
        try {
            return new CalendarDayOverride(
                    resultSet.getObject("calendar_date", LocalDate.class),
                    CalendarDayOverride.DayType.valueOf(resultSet.getString("day_type")),
                    standardMinutes,
                    CalendarDayOverride.Source.valueOf(resultSet.getString("source")),
                    resultSet.getString("note"),
                    resultSet.getLong("row_version")
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored Company calendar configuration is invalid", exception);
        }
    }
}
