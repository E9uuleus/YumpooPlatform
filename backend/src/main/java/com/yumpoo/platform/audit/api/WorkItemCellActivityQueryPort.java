package com.yumpoo.platform.audit.api;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.UUID;

public interface WorkItemCellActivityQueryPort {
    WorkItemCellActivityPage find(UUID companyId, UUID projectId, UUID workItemId,
            ZoneId timezone, DayOfWeek weekStartDay, WorkItemCellActivityQuery query);
}
