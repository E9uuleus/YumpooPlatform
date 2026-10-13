package com.yumpoo.platform.organization.api;

import com.yumpoo.platform.organization.application.CompanyCalendarCommandService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OrganizationCalendarCommandAdapter implements CompanyCalendarCommand {
    private final CompanyCalendarCommandService service;

    public OrganizationCalendarCommandAdapter(CompanyCalendarCommandService service) { this.service = service; }

    @Override
    public long yearVersion(int year) { return service.yearVersion(year); }

    @Override
    public boolean replaceYear(int year, long expectedVersion, List<Day> days, UUID actorUserId, Instant now) {
        return service.replaceYear(year, expectedVersion, days.stream().map(day -> new CompanyCalendarCommandService.Day(
                day.date(), day.dayType().name(), day.standardMinutes(), day.note())).toList(), actorUserId, now);
    }
}
