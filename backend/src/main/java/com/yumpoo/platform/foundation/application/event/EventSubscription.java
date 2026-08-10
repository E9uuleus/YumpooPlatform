package com.yumpoo.platform.foundation.application.event;

public record EventSubscription(String eventType, int eventVersion) {

    public EventSubscription {
        eventType = EventContractRules.eventType(eventType);
        eventVersion = EventContractRules.eventVersion(eventVersion);
    }
}
