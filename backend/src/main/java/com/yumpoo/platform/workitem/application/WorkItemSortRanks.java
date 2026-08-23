package com.yumpoo.platform.workitem.application;

import java.util.Map;
import java.util.UUID;

public record WorkItemSortRanks(
        Map<String, Integer> statuses,
        Map<UUID, Integer> assignees,
        Map<UUID, Integer> reporters
) {
    public WorkItemSortRanks {
        statuses = Map.copyOf(statuses);
        assignees = Map.copyOf(assignees);
        reporters = Map.copyOf(reporters);
    }
}
