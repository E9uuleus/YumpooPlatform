package com.yumpoo.platform.foundation.application.scheduling;

import java.time.Instant;

public record ScheduledJobView(String code, boolean available, boolean enabled, String cron, String zoneId,
        Instant nextRunAt, Instant lastStartedAt, Instant lastFinishedAt, ScheduledJobOutcome lastOutcome,
        String lastErrorCode, String lastRequestId, String etag) {
}
