package com.yumpoo.platform.foundation.application.request;

import java.util.UUID;

/**
 * 一次同步或异步执行链使用的稳定关联标识。
 */
public record RequestCorrelation(
        String requestId,
        String correlationId,
        UUID causationId
) {

    public RequestCorrelation {
        requestId = RequestIdContext.requireValid(requestId, "requestId");
        correlationId = RequestIdContext.requireValid(correlationId, "correlationId");
    }

    public static RequestCorrelation root(String requestId) {
        return new RequestCorrelation(requestId, requestId, null);
    }

    public RequestCorrelation causedBy(UUID eventId) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        return new RequestCorrelation(requestId, correlationId, eventId);
    }
}
