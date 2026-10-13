package com.yumpoo.platform.reporting.application;

import com.yumpoo.platform.catalog.api.CompanyProjectQuery;
import com.yumpoo.platform.catalog.api.MemberProjectQuery;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJob;
import com.yumpoo.platform.foundation.application.scheduling.ScheduledJobResult;
import com.yumpoo.platform.organization.api.CompanyConfigurationQuery;
import com.yumpoo.platform.workitem.api.TeamWorkQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkHoursAnomalyScheduledJob implements ScheduledJob {
    private final WorkHoursAlertRepository repository;
    private final CompanyProjectQuery projects;
    private final TeamWorkQuery work;
    private final CompanyConfigurationQuery company;
    private final TransactionalEventPort events;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkHoursAnomalyScheduledJob(WorkHoursAlertRepository repository, CompanyProjectQuery projects, TeamWorkQuery work,
            CompanyConfigurationQuery company, TransactionalEventPort events, ObjectMapper json, Clock clock) {
        this.repository = repository; this.projects = projects; this.work = work; this.company = company;
        this.events = events; this.json = json; this.clock = clock;
    }

    public String code() { return "WORK_HOURS_ANOMALY"; }

    @Transactional
    public ScheduledJobResult run(UUID companyId, String triggerKey, String requestId) {
        ZoneId zone = company.current().timezone();
        // triggerKey = "scheduled:<code>:<计划时刻 epochSecond>"（ScheduledJobService），检查计划时刻所在自然日的前一天
        Instant planned = Instant.ofEpochSecond(Long.parseLong(triggerKey.substring(triggerKey.lastIndexOf(':') + 1)));
        LocalDate day = LocalDate.ofInstant(planned, zone).minusDays(1);
        int threshold = repository.setting(companyId).map(WorkHoursAlertRepository.Setting::dailyThresholdMinutes)
                .orElse(WorkHoursAlertService.DEFAULT_THRESHOLD_MINUTES);
        List<UUID> projectIds = projects.listForScheduler(companyId).stream()
                .filter(project -> "ACTIVE".equals(project.lifecycle())).map(MemberProjectQuery.Project::id).toList();
        Instant now = clock.instant();
        Map<UUID, Long> totals = new LinkedHashMap<>();
        work.timeEntriesForScheduler(companyId, projectIds, day, day, zone, now).forEach(e -> totals.merge(e.userId(), e.durationMs(), Long::sum));
        totals.forEach((userId, total) -> {
            if (total <= threshold * 60_000L) return;
            repository.recordAlert(companyId, userId, day, total, threshold, now).ifPresent(alertId -> events.append(new EventDraft(
                    "reporting.work_hours_anomaly_detected", 1, "WorkHoursAlert", alertId, 1, companyId, EventActor.system("WORK_HOURS_MONITOR"),
                    json.valueToTree(Map.of("userId", userId, "workDate", day, "totalDurationMs", total, "thresholdMinutes", threshold)))));
        });
        return ScheduledJobResult.succeeded();
    }
}
