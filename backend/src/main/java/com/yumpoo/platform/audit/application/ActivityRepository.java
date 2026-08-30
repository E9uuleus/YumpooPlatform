package com.yumpoo.platform.audit.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ActivityRepository {
    Instant acceptedFrom();
    void append(ActivityStoredEvent event);
    List<ActivityStoredEvent> findScope(UUID companyId, String audience,
            UUID scopeId, Set<String> eventTypes, Set<String> entityTypes,
            Instant occurredFrom, Instant occurredTo, CursorAnchor anchor, int limit);
    List<ActivityStoredEvent> findWorkItem(UUID companyId, UUID projectId, UUID workItemId,
            Set<String> eventTypes, Set<String> entityTypes, Instant occurredFrom,
            Instant occurredTo, CursorAnchor anchor, int limit);

    record CursorAnchor(Instant occurredAt, UUID id) {
    }
}
