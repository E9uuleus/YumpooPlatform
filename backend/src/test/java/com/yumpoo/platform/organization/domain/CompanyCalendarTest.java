package com.yumpoo.platform.organization.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyCalendarTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final CompanyCalendar calendar = new CompanyCalendar();

    @Test
    void resolvesMondayThroughFridayAsWorkdaysAndWeekendsAsNonWorkdays() {
        Company company = company("Asia/Shanghai");

        ResolvedCalendarDay monday = calendar.resolve(company, LocalDate.parse("2028-02-28"), Map.of());
        ResolvedCalendarDay leapDay = calendar.resolve(company, LocalDate.parse("2028-02-29"), Map.of());
        ResolvedCalendarDay sunday = calendar.resolve(company, LocalDate.parse("2028-03-05"), Map.of());

        assertThat(monday.workday()).isTrue();
        assertThat(monday.standardMinutes()).isEqualTo(480);
        assertThat(leapDay.workday()).isTrue();
        assertThat(sunday.workday()).isFalse();
        assertThat(sunday.standardMinutes()).isZero();
        assertThat(monday.explicitOverride()).isFalse();
        assertThat(monday.calendarDayRowVersion()).isEmpty();
    }

    @Test
    void explicitHolidayAndMakeupWorkdayOverrideWeekdayDefaults() {
        Company company = company("Asia/Shanghai");
        LocalDate friday = LocalDate.parse("2026-10-09");
        LocalDate saturday = LocalDate.parse("2026-10-10");
        Map<LocalDate, CalendarDayOverride> overrides = Map.of(
                friday,
                override(friday, CalendarDayOverride.DayType.NON_WORKDAY, 0, 4),
                saturday,
                override(saturday, CalendarDayOverride.DayType.WORKDAY, 420, 5)
        );

        ResolvedCalendarDay holiday = calendar.resolve(company, friday, overrides);
        ResolvedCalendarDay makeupWorkday = calendar.resolve(company, saturday, overrides);

        assertThat(holiday.workday()).isFalse();
        assertThat(holiday.standardMinutes()).isZero();
        assertThat(holiday.calendarDayRowVersion()).hasValue(4);
        assertThat(makeupWorkday.workday()).isTrue();
        assertThat(makeupWorkday.standardMinutes()).isEqualTo(420);
        assertThat(makeupWorkday.calendarDayRowVersion()).hasValue(5);
    }

    @Test
    void workdayWithoutMinuteOverrideInheritsCompanyDefault() {
        Company company = company("Asia/Shanghai");
        LocalDate saturday = LocalDate.parse("2026-10-10");

        ResolvedCalendarDay result = calendar.resolve(
                company,
                saturday,
                Map.of(saturday, override(saturday, CalendarDayOverride.DayType.WORKDAY, null, 2))
        );

        assertThat(result.standardMinutes()).isEqualTo(480);
    }

    @Test
    void companyDateAndWeekBoundaryIgnoreServerDefaultTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Company company = company("Asia/Shanghai");

            LocalDate companyDate = calendar.companyDate(company, Instant.parse("2026-12-31T16:30:00Z"));
            LocalDate weekStart = calendar.weekStart(company, LocalDate.parse("2027-01-03"));

            assertThat(companyDate).isEqualTo(LocalDate.parse("2027-01-01"));
            assertThat(weekStart).isEqualTo(LocalDate.parse("2026-12-28"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void daylightSavingGapMovesForwardToFirstValidInstant() {
        Company company = company("America/New_York");

        Instant result = calendar.toInstant(company, LocalDateTime.parse("2026-03-08T02:30:00"));

        assertThat(result).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    }

    @Test
    void daylightSavingOverlapChoosesEarlierOffset() {
        Company company = company("America/New_York");

        Instant result = calendar.toInstant(company, LocalDateTime.parse("2026-11-01T01:30:00"));

        assertThat(result).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    @Test
    void rejectsNonMondayCompanyWeekConfiguration() {
        assertThatThrownBy(() -> new Company(
                COMPANY_ID,
                "Yumpoo",
                ZoneId.of("Asia/Shanghai"),
                DayOfWeek.SUNDAY,
                480,
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONDAY");
    }

    private static Company company(String timezone) {
        return new Company(
                COMPANY_ID,
                "Yumpoo",
                ZoneId.of(timezone),
                DayOfWeek.MONDAY,
                480,
                3
        );
    }

    private static CalendarDayOverride override(
            LocalDate date,
            CalendarDayOverride.DayType dayType,
            Integer minutes,
            long rowVersion
    ) {
        return new CalendarDayOverride(
                date,
                dayType,
                minutes,
                CalendarDayOverride.Source.MANUAL,
                "test override",
                rowVersion
        );
    }
}
