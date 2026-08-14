package com.yumpoo.platform.audit.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SecurityAuditRecord(
        UUID companyId, String factKey, String action, String outcome,
        String actorType, UUID actorUserId, String actorSystemCode, Set<String> actorRoles,
        String targetType, String targetId, String reasonReference,
        JsonNode beforeSummary, JsonNode afterSummary, String errorCode, UUID commandId,
        String clientType, String clientVersion, Instant occurredAt
) {
    public SecurityAuditRecord {
        actorRoles = Set.copyOf(actorRoles);
    }
}
