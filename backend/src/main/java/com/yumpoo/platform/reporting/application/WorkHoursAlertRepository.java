package com.yumpoo.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface WorkHoursAlertRepository {
    record Setting(int dailyThresholdMinutes, long rowVersion) {}
    Optional<Setting> setting(UUID companyId);
    boolean saveSetting(UUID companyId, int dailyThresholdMinutes, long expectedVersion, UUID actorUserId, Instant now);
    /** Empty when the member already has an alert for that day. */
    Optional<UUID> recordAlert(UUID companyId, UUID userId, LocalDate workDate, long totalDurationMs, int thresholdMinutes, Instant detectedAt);
}
