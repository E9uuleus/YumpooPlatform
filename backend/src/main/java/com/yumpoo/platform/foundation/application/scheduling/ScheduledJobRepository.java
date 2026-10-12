package com.yumpoo.platform.foundation.application.scheduling;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public interface ScheduledJobRepository {
    List<Row> findAll(UUID companyId);
    List<Row> findDue(Instant now);
    boolean save(UUID companyId, String jobCode, boolean enabled, String cron, ZoneId zoneId, Instant nextRunAt,
            UUID actorUserId, Instant now, long expectedVersion);
    boolean claim(UUID companyId, String jobCode, Instant expectedNextRunAt, Instant followingRunAt,
            Instant startedAt, String requestId);
    void finish(UUID companyId, String jobCode, Instant finishedAt, ScheduledJobResult result);

    record Row(UUID companyId, String jobCode, boolean enabled, String cron, ZoneId zoneId, Instant nextRunAt,
            Instant lastStartedAt, Instant lastFinishedAt, ScheduledJobOutcome lastOutcome,
            String lastErrorCode, String lastRequestId, long rowVersion) {}
}
