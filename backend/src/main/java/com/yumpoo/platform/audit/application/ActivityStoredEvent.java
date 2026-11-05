package com.yumpoo.platform.audit.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ActivityStoredEvent(
        UUID id,
        UUID eventId,
        String audienceType,
        UUID companyId,
        UUID scopeId,
        String entityType,
        UUID entityId,
        String entityRef,
        String eventType,
        String actorType,
        UUID actorUserId,
        String actorSystemCode,
        String actorDisplayName,
        Instant occurredAt,
        String templateCode,
        JsonNode safeParameters,
        long entityVersion,
        String requestId,
        String correlationId,
        UUID primaryWorkItemId,
        UUID secondaryWorkItemId) {}
