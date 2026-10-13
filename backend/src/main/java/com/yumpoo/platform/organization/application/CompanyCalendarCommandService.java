package com.yumpoo.platform.organization.application;

import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class CompanyCalendarCommandService {
    private final CompanyRepository repository;

    public CompanyCalendarCommandService(CompanyRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public long yearVersion(int year) { return repository.calendarYearVersion(repository.current().id(), year); }

    @Transactional
    public boolean replaceYear(int year, long expectedVersion, List<Day> days, UUID actorUserId, Instant now) {
        UUID companyId = repository.current().id();
        if (!repository.advanceCalendarYear(companyId, year, expectedVersion, actorUserId, now)) return false;
        repository.replaceCalendarDays(companyId, year, days.stream().map(day -> new CalendarDayOverride(
                day.date(), CalendarDayOverride.DayType.valueOf(day.dayType()),
                "WORKDAY".equals(day.dayType()) ? day.standardMinutes() : Integer.valueOf(0),
                CalendarDayOverride.Source.MANUAL, day.note(), 0)).toList(), now);
        return true;
    }

    public record Day(LocalDate date, String dayType, Integer standardMinutes, String note) {}
}
