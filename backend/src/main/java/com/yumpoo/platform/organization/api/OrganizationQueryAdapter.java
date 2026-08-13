package com.yumpoo.platform.organization.api;

import com.yumpoo.platform.organization.application.OrganizationQueryService;
import com.yumpoo.platform.organization.application.CalendarDayView;
import com.yumpoo.platform.organization.application.CompanyConfigurationView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class OrganizationQueryAdapter implements CompanyConfigurationQuery, CompanyCalendarQuery {

    private final OrganizationQueryService service;

    public OrganizationQueryAdapter(OrganizationQueryService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public CompanyConfigurationSnapshot current() {
        CompanyConfigurationView company = service.currentCompany();
        return new CompanyConfigurationSnapshot(
                company.companyId(),
                company.displayName(),
                company.timezone(),
                company.weekStartDay(),
                company.defaultWorkdayMinutes(),
                company.rowVersion()
        );
    }

    @Override
    public CompanyCalendarDaySnapshot day(LocalDate date) {
        return snapshot(service.days(date, date).getFirst());
    }

    @Override
    public List<CompanyCalendarDaySnapshot> days(LocalDate startInclusive, LocalDate endInclusive) {
        return service.days(startInclusive, endInclusive).stream()
                .map(OrganizationQueryAdapter::snapshot)
                .toList();
    }

    @Override
    public LocalDate companyDate(Instant instant) {
        return service.companyDate(instant);
    }

    @Override
    public CompanyWeek weekContaining(LocalDate date) {
        LocalDate start = service.weekStart(date);
        return new CompanyWeek(start, start.plusDays(6));
    }

    @Override
    public Optional<CompanyCalendarDaySnapshot> lastWorkdayInWeek(LocalDate dateInWeek) {
        return service.lastWorkdayInWeek(dateInWeek).map(OrganizationQueryAdapter::snapshot);
    }

    @Override
    public CompanyCalendarDaySnapshot firstWorkdayOnOrAfter(LocalDate startInclusive) {
        return snapshot(service.firstWorkdayOnOrAfter(startInclusive));
    }

    @Override
    public Instant companyInstant(LocalDateTime localDateTime) {
        return service.companyInstant(localDateTime);
    }

    private static CompanyCalendarDaySnapshot snapshot(CalendarDayView day) {
        return new CompanyCalendarDaySnapshot(
                day.date(),
                CalendarDayType.valueOf(day.dayType()),
                day.standardMinutes(),
                day.explicitOverride(),
                day.companyRowVersion(),
                day.calendarDayRowVersion()
        );
    }
}
