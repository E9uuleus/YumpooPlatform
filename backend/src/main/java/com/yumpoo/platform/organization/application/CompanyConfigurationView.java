package com.yumpoo.platform.organization.application;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;

public record CompanyConfigurationView(
        UUID companyId,
        String displayName,
        ZoneId timezone,
        DayOfWeek weekStartDay,
        int defaultWorkdayMinutes,
        long rowVersion
) {
}
