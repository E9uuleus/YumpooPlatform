package com.yumpoo.platform.audit.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SecurityAuditEventView(
        UUID id,
        UUID companyId,
        String factKey,
        String action,
        SecurityAuditOutcome outcome,
        String actorType,
        UUID actorUserId,
        String actorSystemCode,
        Set<String> actorRoleSnapshot,
        String targetType,
        String targetId,
        String reasonReference,
        JsonNode beforeSummary,
        JsonNode afterSummary,
        String errorCode,
        UUID commandId,
        String requestId,
        String correlationId,
        String clientType,
        String clientVersion,
        Instant occurredAt
) {
}
