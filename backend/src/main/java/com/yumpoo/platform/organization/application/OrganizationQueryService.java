package com.yumpoo.platform.organization.application;

import com.yumpoo.platform.organization.domain.CalendarDayOverride;
import com.yumpoo.platform.organization.domain.Company;
import com.yumpoo.platform.organization.domain.CompanyCalendar;
import com.yumpoo.platform.organization.domain.ResolvedCalendarDay;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class OrganizationQueryService {

    private static final int FIRST_WORKDAY_BATCH_DAYS = 28;

    private final CompanyRepository repository;
    private final CompanyCalendar calendar;

    public OrganizationQueryService(CompanyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.calendar = new CompanyCalendar();
    }

    public CompanyConfigurationView currentCompany() {
        Company company = repository.current();
        return new CompanyConfigurationView(
                company.id(),
                company.displayName(),
                company.timezone(),
                company.weekStartDay(),
                company.defaultWorkdayMinutes(),
                company.rowVersion()
        );
    }

    public List<CalendarDayView> days(LocalDate startInclusive, LocalDate endInclusive) {
        return resolvedDays(startInclusive, endInclusive).stream()
                .map(OrganizationQueryService::view)
                .toList();
    }

    private List<ResolvedCalendarDay> resolvedDays(LocalDate startInclusive, LocalDate endInclusive) {
        requireRange(startInclusive, endInclusive);
        Company company = repository.current();
        Map<LocalDate, CalendarDayOverride> overrides = overrides(
                repository.calendarDays(company.id(), startInclusive, endInclusive)
        );
        List<ResolvedCalendarDay> result = new ArrayList<>();
        LocalDate date = startInclusive;
        while (true) {
            result.add(calendar.resolve(company, date, overrides));
            if (date.equals(endInclusive)) {
                return List.copyOf(result);
            }
            date = date.plusDays(1);
        }
    }

    public LocalDate companyDate(Instant instant) {
        return calendar.companyDate(repository.current(), instant);
    }

    public LocalDate weekStart(LocalDate date) {
        return calendar.weekStart(repository.current(), date);
    }

    public Optional<CalendarDayView> lastWorkdayInWeek(LocalDate dateInWeek) {
        LocalDate start = weekStart(dateInWeek);
        List<ResolvedCalendarDay> days = resolvedDays(start, start.plusDays(6));
        for (int index = days.size() - 1; index >= 0; index--) {
            if (days.get(index).workday()) {
                return Optional.of(view(days.get(index)));
            }
        }
        return Optional.empty();
    }

    public CalendarDayView firstWorkdayOnOrAfter(LocalDate startInclusive) {
        Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        LocalDate cursor = startInclusive;
        while (true) {
            LocalDate end = cursor.plusDays(FIRST_WORKDAY_BATCH_DAYS - 1L);
            for (ResolvedCalendarDay day : resolvedDays(cursor, end)) {
                if (day.workday()) {
                    return view(day);
                }
            }
            cursor = end.plusDays(1);
        }
    }

    public Instant companyInstant(LocalDateTime localDateTime) {
        return calendar.toInstant(repository.current(), localDateTime);
    }

    private static Map<LocalDate, CalendarDayOverride> overrides(List<CalendarDayOverride> records) {
        Map<LocalDate, CalendarDayOverride> result = new HashMap<>();
        for (CalendarDayOverride record : records) {
            if (result.put(record.date(), record) != null) {
                throw new IllegalStateException("Duplicate Company calendar date returned by repository");
            }
        }
        return Map.copyOf(result);
    }

    private static CalendarDayView view(ResolvedCalendarDay day) {
        return new CalendarDayView(
                day.date(),
                day.dayType().name(),
                day.standardMinutes(),
                day.explicitOverride(),
                day.companyRowVersion(),
                day.calendarDayRowVersion()
        );
    }

    private static void requireRange(LocalDate startInclusive, LocalDate endInclusive) {
        Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        Objects.requireNonNull(endInclusive, "endInclusive must not be null");
        if (endInclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException("endInclusive must not be before startInclusive");
        }
    }
}
