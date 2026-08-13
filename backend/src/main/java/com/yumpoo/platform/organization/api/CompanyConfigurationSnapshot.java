package com.yumpoo.platform.organization.api;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record CompanyConfigurationSnapshot(
        UUID companyId,
        String displayName,
        ZoneId timezone,
        DayOfWeek weekStartDay,
        int defaultWorkdayMinutes,
        long rowVersion
) {

    public CompanyConfigurationSnapshot {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(weekStartDay, "weekStartDay must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (weekStartDay != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStartDay must be MONDAY");
        }
        if (defaultWorkdayMinutes < 1 || defaultWorkdayMinutes > 720) {
            throw new IllegalArgumentException("defaultWorkdayMinutes must be between 1 and 720");
        }
        if (rowVersion < 0) {
            throw new IllegalArgumentException("rowVersion must not be negative");
        }
    }
}
