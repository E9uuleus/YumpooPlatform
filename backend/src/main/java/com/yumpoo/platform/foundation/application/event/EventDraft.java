package com.yumpoo.platform.foundation.application.event;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 业务模块发布事件时提供的最小事实；技术信封字段由 foundation 补齐。
 */
public record EventDraft(
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        UUID companyId,
        EventActor actor,
        JsonNode payload
) {

    public EventDraft {
        eventType = EventContractRules.eventType(eventType);
        eventVersion = EventContractRules.eventVersion(eventVersion);
        aggregateType = EventContractRules.aggregateType(aggregateType);
        aggregateVersion = EventContractRules.aggregateVersion(aggregateVersion);
        if (aggregateId == null || companyId == null || actor == null) {
            throw new IllegalArgumentException("aggregateId, companyId and actor must not be null");
        }
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        payload = payload.deepCopy();
    }
}
