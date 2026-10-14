package com.yumpoo.platform.organization.application;

import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import com.yumpoo.platform.organization.domain.Company;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CompanyRepository {

    Company current();

    boolean rename(UUID companyId, String displayName, long expectedVersion, Instant now);

    long calendarYearVersion(UUID companyId, int year);

    boolean advanceCalendarYear(UUID companyId, int year, long expectedVersion, UUID actorUserId, Instant now);

    void replaceCalendarDays(UUID companyId, int year, List<CalendarDayOverride> days, Instant now);

    List<CalendarDayOverride> calendarDays(
            UUID companyId,
            LocalDate startInclusive,
            LocalDate endInclusive
    );
}
