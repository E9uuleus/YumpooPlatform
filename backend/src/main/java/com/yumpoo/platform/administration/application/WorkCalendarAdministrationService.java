package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.organization.api.CalendarDayType;
import com.yumpoo.platform.organization.api.CompanyCalendarCommand;
import com.yumpoo.platform.organization.api.CompanyCalendarDaySnapshot;
import com.yumpoo.platform.organization.api.CompanyCalendarQuery;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkCalendarAdministrationService {
    private final CompanyCalendarQuery calendar;
    private final CompanyCalendarCommand commands;
    private final CompanyConfigurationQuery company;
    private final SecurityAuditAppendPort audits;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkCalendarAdministrationService(CompanyCalendarQuery calendar, CompanyCalendarCommand commands,
            CompanyConfigurationQuery company, SecurityAuditAppendPort audits, ObjectMapper json, Clock clock) {
        this.calendar = calendar; this.commands = commands; this.company = company;
        this.audits = audits; this.json = json; this.clock = clock;
    }

    public record Year(int year, int defaultWorkdayMinutes, long version, List<CompanyCalendarDaySnapshot> days) {}

    @Transactional(readOnly = true)
    public Year year(CurrentActor actor, int year) {
        requireAdmin(actor); requireYear(year);
        return new Year(year, company.current().defaultWorkdayMinutes(), commands.yearVersion(year),
                calendar.days(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)));
    }

    @Transactional
    public Year replace(CurrentActor actor, int year, long expectedVersion, List<CompanyCalendarCommand.Day> days) {
        requireAdmin(actor); requireYear(year);
        Set<LocalDate> dates = new HashSet<>();
        for (var day : days) {
            if (day.date().getYear() != year || !dates.add(day.date())) throw invalid("days", "日期须属于 " + year + " 年且不能重复");
            if (day.dayType() != CalendarDayType.WORKDAY && day.standardMinutes() != null) throw invalid("days", "只有工作日可以设置标准工时");
        }
        Instant now = clock.instant();
        if (!commands.replaceYear(year, expectedVersion, days, actor.userId(), now)) throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        audits.append(new SecurityAuditDraft(actor.companyId(), "company-calendar-year:" + year + ":" + (expectedVersion + 1),
                "COMPANY_CALENDAR_YEAR_REPLACED", SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(actor.userId(), ProjectLifecycleGovernanceService.roleNames(actor)),
                "COMPANY_CALENDAR_YEAR", Integer.toString(year), null, null, json.valueToTree(Map.of("explicitDays", days.size())),
                null, null, null, null, now));
        return year(actor, year);
    }

    private static void requireAdmin(CurrentActor actor) {
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
    }

    private static void requireYear(int year) {
        if (year < 2000 || year > 2100) throw invalid("year", "年份需在 2000–2100 之间");
    }

    private static ApplicationException invalid(String field, String message) {
        return ApplicationException.validation(new FieldViolation(field, "INVALID_VALUE", message));
    }
}
