package com.yumpoo.platform.organization.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalLong;

public record ResolvedCalendarDay(
        LocalDate date,
        CalendarDayOverride.DayType dayType,
        int standardMinutes,
        boolean explicitOverride,
        long companyRowVersion,
        OptionalLong calendarDayRowVersion
) {

    public ResolvedCalendarDay {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(dayType, "dayType must not be null");
        Objects.requireNonNull(calendarDayRowVersion, "calendarDayRowVersion must not be null");
    }

    public boolean workday() {
        return dayType == CalendarDayOverride.DayType.WORKDAY;
    }
}
