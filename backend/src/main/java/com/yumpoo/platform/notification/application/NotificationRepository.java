package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.notification.application.NotificationModels.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface NotificationRepository {
    record Event(UUID id, UUID companyId, UUID sourceEventId, String eventType, int version,
            TargetKind kind, UUID projectId, UUID workItemId, UUID updateId, UUID subjectUserId,
            UUID actorUserId, Instant occurredAt) {}
    record Row(UUID id, Reason reason, State state, Instant createdAt, Instant readAt, Event event) {}
    record Anchor(Instant createdAt, UUID id) {}
    Instant acceptedFrom();
    void append(Event event, Map<UUID, Reason> recipients);
    List<Row> find(UUID companyId, UUID userId, ListState state, Group group, Anchor before, int limit);
    UnreadCounts counts(UUID companyId, UUID userId);
    Instant serverNow();
    boolean setState(UUID companyId, UUID userId, UUID id, State state);
    void readAll(UUID companyId, UUID userId, Instant upTo, Group group);
}
