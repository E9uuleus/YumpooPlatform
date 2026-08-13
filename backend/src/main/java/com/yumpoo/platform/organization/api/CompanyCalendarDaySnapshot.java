package com.yumpoo.platform.organization.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalLong;

public record CompanyCalendarDaySnapshot(
        LocalDate date,
        CalendarDayType dayType,
        int standardMinutes,
        boolean explicitOverride,
        long companyRowVersion,
        OptionalLong calendarDayRowVersion
) {

    public CompanyCalendarDaySnapshot {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(dayType, "dayType must not be null");
        Objects.requireNonNull(calendarDayRowVersion, "calendarDayRowVersion must not be null");
        if (standardMinutes < 0 || standardMinutes > 720) {
            throw new IllegalArgumentException("standardMinutes must be between 0 and 720");
        }
        if (dayType == CalendarDayType.NON_WORKDAY && standardMinutes != 0) {
            throw new IllegalArgumentException("NON_WORKDAY standardMinutes must be zero");
        }
        if (dayType == CalendarDayType.WORKDAY && standardMinutes == 0) {
            throw new IllegalArgumentException("WORKDAY standardMinutes must be positive");
        }
        if (companyRowVersion < 0) {
            throw new IllegalArgumentException("companyRowVersion must not be negative");
        }
        if (explicitOverride != calendarDayRowVersion.isPresent()) {
            throw new IllegalArgumentException("calendarDayRowVersion must identify explicit overrides only");
        }
        if (calendarDayRowVersion.isPresent() && calendarDayRowVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("calendarDayRowVersion must not be negative");
        }
    }
}
