package com.yumpoo.platform.foundation.application.scheduling;

import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScheduledJobService {
    public static final String DEFAULT_CRON = "0 0 2 * * *";
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledJobService.class);

    private final Map<String, ScheduledJob> jobs;
    private final ScheduledJobRepository repository;
    private final Clock clock;

    public ScheduledJobService(List<ScheduledJob> jobs, ScheduledJobRepository repository, Clock clock) {
        this.jobs = jobs.stream().collect(Collectors.toUnmodifiableMap(ScheduledJob::code, Function.identity()));
        this.repository = repository;
        this.clock = clock;
    }

    public List<ScheduledJobView> list(UUID companyId, ZoneId companyZone) {
        Map<String, ScheduledJobRepository.Row> rows = repository.findAll(companyId).stream()
                .collect(Collectors.toMap(ScheduledJobRepository.Row::jobCode, Function.identity()));
        return jobs.keySet().stream().sorted().map(code -> {
            ScheduledJob job = jobs.get(code);
            ScheduledJobRepository.Row row = rows.get(code);
            return row == null
                    ? new ScheduledJobView(code, job.available(), false, DEFAULT_CRON, companyZone.getId(),
                            null, null, null, null, null, null, StrongEtag.format(0))
                    : new ScheduledJobView(code, job.available(), row.enabled(), row.cron(), row.zoneId().getId(),
                            row.nextRunAt(), row.lastStartedAt(), row.lastFinishedAt(), row.lastOutcome(),
                            row.lastErrorCode(), row.lastRequestId(), StrongEtag.format(row.rowVersion()));
        }).toList();
    }

    public boolean exists(String jobCode) {
        return jobs.containsKey(jobCode);
    }

    public ScheduledJobView update(UUID companyId, String jobCode, boolean enabled, String cron,
            ZoneId companyZone, UUID actorUserId, long expectedVersion) {
        if (!exists(jobCode)) throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        String rule = cron.strip();
        if (!CronExpression.isValidExpression(rule)) throw invalidCron();
        Instant now = clock.instant();
        Instant next = nextRunAt(rule, companyZone, now);
        if (next == null) throw invalidCron();
        if (!repository.save(companyId, jobCode, enabled, rule, companyZone,
                enabled ? next : null, actorUserId, now, expectedVersion)) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
        return list(companyId, companyZone).stream().filter(view -> view.code().equals(jobCode)).findFirst().orElseThrow();
    }

    public static Instant nextRunAt(String cron, ZoneId zone, Instant after) {
        var next = CronExpression.parse(cron).next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    public void runDue() {
        Instant now = clock.instant();
        for (ScheduledJobRepository.Row row : repository.findDue(now)) {
            ScheduledJob job = jobs.get(row.jobCode());
            if (job == null) continue;
            String requestId = UUID.randomUUID().toString();
            if (!repository.claim(row.companyId(), row.jobCode(), row.nextRunAt(),
                    nextRunAt(row.cron(), row.zoneId(), now), now, requestId)) continue;
            long started = System.nanoTime();
            try (RequestCorrelationContext.Scope ignoredCorrelation =
                         RequestCorrelationContext.open(RequestCorrelation.root(requestId));
                 StructuredLoggingContext.Scope ignoredLogging = StructuredLoggingContext.open(Map.of(
                         StructuredLoggingContext.REQUEST_ID, requestId,
                         StructuredLoggingContext.CORRELATION_ID, requestId,
                         StructuredLoggingContext.JOB, row.jobCode()))) {
                ScheduledJobResult result;
                RuntimeException cause = null;
                try {
                    result = job.run(row.companyId(),
                            "scheduled:" + row.jobCode() + ":" + row.nextRunAt().getEpochSecond(), requestId);
                } catch (RuntimeException exception) {
                    result = new ScheduledJobResult(ScheduledJobOutcome.FAILED, "SCHEDULED_JOB_UNEXPECTED_FAILURE");
                    cause = exception;
                }
                repository.finish(row.companyId(), row.jobCode(), clock.instant(), result);
                String event = switch (result.outcome()) {
                    case SUCCEEDED, PARTIAL -> "scheduler.job.completed";
                    case FAILED -> "scheduler.job.failed";
                    case SKIPPED -> "scheduler.job.skipped";
                };
                var log = LOGGER.atLevel(result.outcome() == ScheduledJobOutcome.FAILED ? Level.WARN : Level.INFO)
                        .setMessage("scheduled job finalized").addKeyValue("event", event)
                        .addKeyValue("outcome", result.outcome()).addKeyValue("errorCode", result.errorCode())
                        .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000);
                if (cause != null) log.setCause(cause);
                log.log();
            }
        }
    }

    private static ApplicationException invalidCron() {
        return new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new FieldViolation("cron", "INVALID_CRON", "调度规则无效")));
    }
}
