package com.yumpoo.platform.foundation.application.event;

import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 持久化和消费使用的稳定事件信封。
 */
public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        UUID companyId,
        EventActor actor,
        String requestId,
        String correlationId,
        UUID causationId,
        JsonNode payload
) {

    public DomainEventEnvelope {
        if (eventId == null || occurredAt == null || aggregateId == null || companyId == null || actor == null) {
            throw new IllegalArgumentException("event envelope identity fields must not be null");
        }
        if (eventId.version() != 4) {
            throw new IllegalArgumentException("eventId must be UUIDv4");
        }
        eventType = EventContractRules.eventType(eventType);
        eventVersion = EventContractRules.eventVersion(eventVersion);
        aggregateType = EventContractRules.aggregateType(aggregateType);
        aggregateVersion = EventContractRules.aggregateVersion(aggregateVersion);
        requestId = RequestIdContext.requireValid(requestId, "requestId");
        correlationId = RequestIdContext.requireValid(correlationId, "correlationId");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        payload = payload.deepCopy();
    }
}
