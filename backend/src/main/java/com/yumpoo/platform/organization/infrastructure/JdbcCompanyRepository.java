package com.yumpoo.platform.organization.infrastructure;

import com.yumpoo.platform.organization.application.CompanyRepository;
import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import com.yumpoo.platform.organization.domain.Company;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
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

    public JdbcCompanyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
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
