package com.yumpoo.platform.audit.api;

import java.util.List;
import java.util.UUID;

public record WorkItemCellActivityQuery(String cursor, Integer size,
        WorkItemCellActivityTimeRange timeRange, List<UUID> actorUserIds,
        List<WorkItemCellActivityColumn> columns) {}
