package com.yumpoo.platform.administration.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GovernanceOverrideRecord(
        UUID id,
        UUID companyId,
        GovernanceOverrideAction action,
        String targetType,
        UUID targetId,
        String reason,
        String requestHash,
        UUID idempotencyKey,
        UUID actorUserId,
        JsonNode beforeSnapshot,
        JsonNode afterSnapshot,
        JsonNode blockerCounts,
        GovernanceOverrideResult result,
        String errorCode,
        Instant occurredAt
) {
    public GovernanceOverrideRecord {
        Objects.requireNonNull(id); Objects.requireNonNull(companyId); Objects.requireNonNull(action);
        Objects.requireNonNull(targetId); Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(actorUserId); Objects.requireNonNull(beforeSnapshot);
        Objects.requireNonNull(blockerCounts); Objects.requireNonNull(result); Objects.requireNonNull(occurredAt);
        targetType = requireText(targetType, 32, "targetType");
        reason = requireText(reason, 500, "reason");
        if (reason.length() < 10) throw new IllegalArgumentException("reason must contain at least 10 characters");
        if (requestHash == null || !requestHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("requestHash must be lowercase sha-256");
        }
        if (result == GovernanceOverrideResult.SUCCEEDED && (afterSnapshot == null || errorCode != null)) {
            throw new IllegalArgumentException("successful override result is inconsistent");
        }
        if (result == GovernanceOverrideResult.FAILED && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("failed override requires errorCode");
        }
    }

    private static String requireText(String value, int max, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || !value.equals(value.strip()) || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }
}
