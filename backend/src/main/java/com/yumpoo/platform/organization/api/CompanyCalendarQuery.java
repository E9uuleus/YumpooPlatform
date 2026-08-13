package com.yumpoo.platform.organization.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CompanyCalendarQuery {

    CompanyCalendarDaySnapshot day(LocalDate date);

    List<CompanyCalendarDaySnapshot> days(LocalDate startInclusive, LocalDate endInclusive);

    LocalDate companyDate(Instant instant);

    CompanyWeek weekContaining(LocalDate date);

    Optional<CompanyCalendarDaySnapshot> lastWorkdayInWeek(LocalDate dateInWeek);

    CompanyCalendarDaySnapshot firstWorkdayOnOrAfter(LocalDate startInclusive);

    Instant companyInstant(LocalDateTime localDateTime);
}
