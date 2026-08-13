package com.yumpoo.platform.organization.application;

import java.time.LocalDate;
import java.util.OptionalLong;

public record CalendarDayView(
        LocalDate date,
        String dayType,
        int standardMinutes,
        boolean explicitOverride,
        long companyRowVersion,
        OptionalLong calendarDayRowVersion
) {
}
