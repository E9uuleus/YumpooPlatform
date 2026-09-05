package com.yumpoo.platform.audit.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface WorkItemCellActivityRepository {
    Instant acceptedFrom();
    void append(WorkItemCellActivityStoredEvent event);
    List<WorkItemCellActivityStoredEvent> find(UUID companyId, UUID workItemId,
            Filters filters, CursorAnchor anchor, int limit);
    List<WorkItemCellActivityStoredEvent> findForFacets(UUID companyId, UUID workItemId,
            Filters filters);

    record Filters(Instant occurredFrom, Instant occurredTo, Instant snapshotAt,
            Set<UUID> actorUserIds, Set<String> columns) {}
    record CursorAnchor(Instant occurredAt, UUID id) {}
}
