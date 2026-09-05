package com.yumpoo.platform.audit.api;

import java.time.Instant;
import java.util.List;

public record WorkItemCellActivityPage(List<WorkItemCellActivityEntry> items, String nextCursor,
        Instant historyStartedAt, WorkItemCellActivityFacets facets) {}
