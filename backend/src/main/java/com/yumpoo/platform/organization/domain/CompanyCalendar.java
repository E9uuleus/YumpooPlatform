package com.yumpoo.platform.organization.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

public final class CompanyCalendar {

    public LocalDate companyDate(Company company, Instant instant) {
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(instant, "instant must not be null");
        return instant.atZone(company.timezone()).toLocalDate();
    }

    public LocalDate weekStart(Company company, LocalDate date) {
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(date, "date must not be null");
        if (company.weekStartDay() != DayOfWeek.MONDAY) {
            throw new IllegalStateException("Company week start must be MONDAY");
        }
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public ResolvedCalendarDay resolve(
            Company company,
            LocalDate date,
            Map<LocalDate, CalendarDayOverride> overrides
    ) {
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(overrides, "overrides must not be null");
        CalendarDayOverride override = overrides.get(date);
        if (override != null) {
            int minutes = override.dayType() == CalendarDayOverride.DayType.NON_WORKDAY
                    ? 0
                    : override.standardMinutes() == null
                    ? company.defaultWorkdayMinutes()
                    : override.standardMinutes();
            return new ResolvedCalendarDay(
                    date,
                    override.dayType(),
                    minutes,
                    true,
                    company.rowVersion(),
                    OptionalLong.of(override.rowVersion())
            );
        }

        boolean weekday = date.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue();
        return new ResolvedCalendarDay(
                date,
                weekday ? CalendarDayOverride.DayType.WORKDAY : CalendarDayOverride.DayType.NON_WORKDAY,
                weekday ? company.defaultWorkdayMinutes() : 0,
                false,
                company.rowVersion(),
                OptionalLong.empty()
        );
    }

    public Instant toInstant(Company company, LocalDateTime localDateTime) {
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(localDateTime, "localDateTime must not be null");
        ZoneRules rules = company.timezone().getRules();
        List<ZoneOffset> validOffsets = rules.getValidOffsets(localDateTime);
        if (validOffsets.size() == 1) {
            return localDateTime.toInstant(validOffsets.getFirst());
        }
        if (validOffsets.size() == 2) {
            return localDateTime.toInstant(validOffsets.getFirst());
        }

        ZoneOffsetTransition transition = rules.getTransition(localDateTime);
        if (transition == null || !transition.isGap()) {
            throw new IllegalStateException("Unable to resolve Company local time");
        }
        return transition.getDateTimeAfter().toInstant(transition.getOffsetAfter());
    }
}
