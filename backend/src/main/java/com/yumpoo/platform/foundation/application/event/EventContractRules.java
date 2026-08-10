package com.yumpoo.platform.foundation.application.event;

import java.util.regex.Pattern;

final class EventContractRules {

    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
    );

    private EventContractRules() {
    }

    static String eventType(String value) {
        if (value == null || value.length() > 120 || !EVENT_TYPE.matcher(value).matches()) {
            throw new IllegalArgumentException("eventType must use a lowercase dotted namespace");
        }
        return value;
    }

    static int eventVersion(int value) {
        if (value <= 0 || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException("eventVersion must be between 1 and 32767");
        }
        return value;
    }

    static String aggregateType(String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new IllegalArgumentException("aggregateType must be between 1 and 80 characters");
        }
        return value;
    }

    static long aggregateVersion(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("aggregateVersion must not be negative");
        }
        return value;
    }
}
