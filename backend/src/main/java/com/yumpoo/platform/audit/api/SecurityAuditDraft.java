package com.yumpoo.platform.audit.api;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SecurityAuditDraft(
        UUID companyId,
        String factKey,
        String action,
        SecurityAuditOutcome outcome,
        SecurityAuditActor actor,
        String targetType,
        String targetId,
        String reasonReference,
        JsonNode beforeSummary,
        JsonNode afterSummary,
        String errorCode,
        UUID commandId,
        String clientType,
        String clientVersion,
        Instant occurredAt
) {
    public SecurityAuditDraft {
        Objects.requireNonNull(companyId, "companyId must not be null");
        factKey = requireText(factKey, "factKey", 200);
        action = requireText(action, "action", 96);
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        targetType = requireText(targetType, "targetType", 64);
        targetId = requireText(targetId, "targetId", 128);
        if (reasonReference != null) {
            reasonReference = requireText(reasonReference, "reasonReference", 160);
        }
        if (outcome == SecurityAuditOutcome.SUCCEEDED && errorCode != null) {
            throw new IllegalArgumentException("successful audit must not contain errorCode");
        }
        if (outcome != SecurityAuditOutcome.SUCCEEDED) {
            errorCode = requireText(errorCode, "errorCode", 64);
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be trimmed and between 1 and " + maximum);
        }
        return value;
    }
}
