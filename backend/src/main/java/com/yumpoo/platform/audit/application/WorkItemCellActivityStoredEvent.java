package com.yumpoo.platform.audit.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record WorkItemCellActivityStoredEvent(
        UUID id,
        UUID eventId,
        UUID companyId,
        UUID projectId,
        UUID workItemId,
        UUID contentId,
        String contentDisplayName,
        String eventType,
        String columnCode,
        String changeType,
        JsonNode beforeValue,
        JsonNode afterValue,
        String actorType,
        UUID actorUserId,
        String actorSystemCode,
        String actorDisplayName,
        Instant occurredAt,
        String requestId,
        String correlationId
) {}
