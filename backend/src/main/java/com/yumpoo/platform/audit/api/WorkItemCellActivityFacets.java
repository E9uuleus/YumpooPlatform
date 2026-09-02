package com.yumpoo.platform.audit.api;

import java.util.List;
import java.util.UUID;

public record WorkItemCellActivityFacets(List<TimeRangeFacet> timeRanges,
        List<ActorFacet> actors, List<ColumnFacet> columns) {
    public record TimeRangeFacet(WorkItemCellActivityTimeRange value, long count,
            boolean selected) {}
    public record ActorFacet(UUID userId, String displayName, long count, boolean selected) {}
    public record ColumnFacet(WorkItemCellActivityColumn value, long count, boolean selected) {}
}
