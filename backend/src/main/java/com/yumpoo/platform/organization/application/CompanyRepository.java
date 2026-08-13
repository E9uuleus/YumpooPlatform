package com.yumpoo.platform.organization.application;

import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import com.yumpoo.platform.organization.domain.Company;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CompanyRepository {

    Company current();

    List<CalendarDayOverride> calendarDays(
            UUID companyId,
            LocalDate startInclusive,
            LocalDate endInclusive
    );
}
