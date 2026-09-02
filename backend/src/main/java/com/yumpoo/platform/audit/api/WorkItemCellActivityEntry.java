package com.yumpoo.platform.audit.api;

import java.time.Instant;
import java.util.UUID;

public record WorkItemCellActivityEntry(UUID id, String eventType,
        ActivityActorView actor, Instant occurredAt, WorkItemCellActivityColumn column,
        WorkItemCellActivityChangeType changeType, WorkItemCellActivityValue beforeValue,
        WorkItemCellActivityValue afterValue, UUID contentId, String contentDisplayName) {}
