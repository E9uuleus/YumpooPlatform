package com.yumpoo.platform.organization.infrastructure;

import com.yumpoo.platform.organization.api.CalendarDayType;
import com.yumpoo.platform.organization.api.CompanyCalendarDaySnapshot;
import com.yumpoo.platform.organization.api.CompanyCalendarQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationSnapshot;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Types;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OrganizationQueriesIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-13T03:00:00Z");

    @Autowired
    private CompanyConfigurationQuery companyConfigurationQuery;

    @Autowired
    private CompanyCalendarQuery companyCalendarQuery;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetCompanyAndCalendar() {
        jdbcClient.sql("TRUNCATE TABLE yumpoo.company CASCADE").update();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.company (
                            id, singleton_slot, display_name, timezone, week_start_day,
                            default_workday_minutes, row_version, created_at, updated_at
                        ) VALUES (
                            :id, 1, 'Yumpoo', 'Asia/Shanghai', 'MONDAY',
                            480, 0, :now, :now
                        )
                        """)
                .param("id", COMPANY_ID)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }

    @AfterEach
    void restoreCompanyFixture() {
        resetCompanyAndCalendar();
    }

    @Test
    void readsSeededCompanyConfiguration() {
        CompanyConfigurationSnapshot current = companyConfigurationQuery.current();

        assertThat(current.companyId()).isEqualTo(COMPANY_ID);
        assertThat(current.displayName()).isEqualTo("Yumpoo");
        assertThat(current.timezone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(current.weekStartDay()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(current.defaultWorkdayMinutes()).isEqualTo(480);
        assertThat(current.rowVersion()).isZero();
    }

    @Test
    void readsClosedRangeWithExplicitOverridesAndVersionMetadata() {
        insertCalendarDay("2026-10-09", "NON_WORKDAY", 0, "IMPORT", 7);
        insertCalendarDay("2026-10-10", "WORKDAY", null, "MANUAL", 8);

        List<CompanyCalendarDaySnapshot> days = companyCalendarQuery.days(
                LocalDate.parse("2026-10-09"),
                LocalDate.parse("2026-10-11")
        );

        assertThat(days).extracting(CompanyCalendarDaySnapshot::date).containsExactly(
                LocalDate.parse("2026-10-09"),
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-11")
        );
        assertThat(days).extracting(CompanyCalendarDaySnapshot::dayType).containsExactly(
                CalendarDayType.NON_WORKDAY,
                CalendarDayType.WORKDAY,
                CalendarDayType.NON_WORKDAY
        );
        assertThat(days).extracting(CompanyCalendarDaySnapshot::standardMinutes)
                .containsExactly(0, 480, 0);
        assertThat(days).extracting(CompanyCalendarDaySnapshot::explicitOverride)
                .containsExactly(true, true, false);
        assertThat(days.get(0).calendarDayRowVersion()).hasValue(7);
        assertThat(days.get(1).calendarDayRowVersion()).hasValue(8);
        assertThat(days.get(2).calendarDayRowVersion()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void findsLastWorkdayAndContinuesBeyondAWholeNonWorkdayWeek() {
        LocalDate firstHoliday = LocalDate.parse("2026-10-05");
        for (int offset = 0; offset < 7; offset++) {
            insertCalendarDay(firstHoliday.plusDays(offset).toString(), "NON_WORKDAY", 0, "IMPORT", 0);
        }
        insertCalendarDay("2026-10-03", "WORKDAY", 420, "IMPORT", 1);

        CompanyCalendarDaySnapshot lastWorkday = companyCalendarQuery
                .lastWorkdayInWeek(LocalDate.parse("2026-09-30"))
                .orElseThrow();
        CompanyCalendarDaySnapshot firstAfterHoliday = companyCalendarQuery
                .firstWorkdayOnOrAfter(firstHoliday);

        assertThat(lastWorkday.date()).isEqualTo(LocalDate.parse("2026-10-03"));
        assertThat(lastWorkday.standardMinutes()).isEqualTo(420);
        assertThat(companyCalendarQuery.lastWorkdayInWeek(firstHoliday)).isEmpty();
        assertThat(firstAfterHoliday.date()).isEqualTo(LocalDate.parse("2026-10-12"));
    }

    @Test
    void convertsInstantsAndWeekBoundariesUsingStoredCompanyTimezone() {
        jdbcClient.sql("UPDATE yumpoo.company SET timezone = 'America/New_York', row_version = 1")
                .update();

        LocalDate date = companyCalendarQuery.companyDate(Instant.parse("2027-01-01T03:30:00Z"));
        var week = companyCalendarQuery.weekContaining(LocalDate.parse("2027-01-03"));

        assertThat(date).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(week.startInclusive()).isEqualTo(LocalDate.parse("2026-12-28"));
        assertThat(week.endInclusive()).isEqualTo(LocalDate.parse("2027-01-03"));
    }

    @Test
    void failsClosedWhenCompanyIsMissingOrTimezoneIsInvalid() {
        jdbcClient.sql("""
                DO $$
                BEGIN
                    DELETE FROM yumpoo.workspace;
                    DELETE FROM yumpoo.company;
                END $$
                """).update();
        assertThatThrownBy(companyConfigurationQuery::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Exactly one Company");

        resetCompanyAndCalendar();
        jdbcClient.sql("UPDATE yumpoo.company SET timezone = 'Not/A_Real_Zone'").update();
        assertThatThrownBy(companyConfigurationQuery::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void databasePreventsASecondCompanyAndDuplicateCalendarDate() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO yumpoo.company (
                            id, singleton_slot, display_name, timezone, week_start_day,
                            default_workday_minutes, row_version, created_at, updated_at
                        ) VALUES (
                            '00000000-0000-4000-8000-000000000002', 1, 'Second',
                            'UTC', 'MONDAY', 480, 0, :now, :now
                        )
                        """).param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        insertCalendarDay("2026-08-13", "WORKDAY", 480, "MANUAL", 0);
        assertThatThrownBy(() -> insertCalendarDay("2026-08-13", "NON_WORKDAY", 0, "IMPORT", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonMondayWeekStart() {
        assertThatThrownBy(() -> jdbcClient.sql(
                        "UPDATE yumpoo.company SET week_start_day = 'SUNDAY' WHERE id = :id"
                ).param("id", COMPANY_ID).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidCalendarDayCombinations")
    void databaseRejectsInvalidCalendarDayCombinations(
            String dayType,
            Integer standardMinutes,
            String source
    ) {
        assertThatThrownBy(() -> insertCalendarDay(
                "2026-08-14",
                dayType,
                standardMinutes,
                source,
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    static Stream<Arguments> invalidCalendarDayCombinations() {
        return Stream.of(
                Arguments.of("WORKDAY", 0, "MANUAL"),
                Arguments.of("WORKDAY", 721, "MANUAL"),
                Arguments.of("NON_WORKDAY", null, "MANUAL"),
                Arguments.of("NON_WORKDAY", 480, "MANUAL"),
                Arguments.of("UNKNOWN", 0, "MANUAL"),
                Arguments.of("WORKDAY", 480, "REMOTE")
        );
    }

    private void insertCalendarDay(
            String date,
            String dayType,
            Integer standardMinutes,
            String source,
            long rowVersion
    ) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.company_calendar_day (
                            company_id, calendar_date, day_type, standard_minutes,
                            source, note, row_version, created_at, updated_at
                        ) VALUES (
                            :companyId, :calendarDate, :dayType, :standardMinutes,
                            :source, 'integration test', :rowVersion, :now, :now
                        )
                        """)
                .param("companyId", COMPANY_ID)
                .param("calendarDate", LocalDate.parse(date))
                .param("dayType", dayType)
                .param("standardMinutes", standardMinutes, Types.INTEGER)
                .param("source", source)
                .param("rowVersion", rowVersion)
                .param("now", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
    }
}
