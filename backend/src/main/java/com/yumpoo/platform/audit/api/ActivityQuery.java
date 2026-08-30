package com.yumpoo.platform.audit.api;

import java.time.Instant;
import java.util.List;

public record ActivityQuery(
        String cursor,
        Integer size,
        List<String> eventTypes,
        List<String> entityTypes,
        Instant occurredFrom,
        Instant occurredTo
) {
    public ActivityQuery {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
    }
}
