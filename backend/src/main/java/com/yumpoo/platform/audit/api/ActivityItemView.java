package com.yumpoo.platform.audit.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ActivityItemView(
        UUID id,
        ActivityAudienceType audienceType,
        String sourceEventType,
        String entityType,
        UUID entityId,
        String entityRef,
        List<UUID> relatedWorkItemIds,
        ActivityActorView actor,
        Instant occurredAt,
        String templateCode,
        String summary,
        JsonNode safeParameters,
        String requestId
) {
    public ActivityItemView {
        relatedWorkItemIds = List.copyOf(relatedWorkItemIds);
        safeParameters = safeParameters.deepCopy();
    }
}
