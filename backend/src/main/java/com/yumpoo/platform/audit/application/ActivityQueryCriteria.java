package com.yumpoo.platform.audit.application;

import java.time.Instant;
import java.util.Set;

public record ActivityQueryCriteria(String cursor, Integer size, Set<String> eventTypes,
        Set<String> entityTypes, Instant occurredFrom, Instant occurredTo) {
    public ActivityQueryCriteria {
        eventTypes = Set.copyOf(eventTypes);
        entityTypes = Set.copyOf(entityTypes);
    }
}
