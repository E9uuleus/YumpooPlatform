package com.yumpoo.platform.foundation.application.logging;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record LogRecord(
    Instant time,
    String level,
    String module,
    String event,
    String msg,
    String logger,
    String thread,
    String requestId,
    String correlationId,
    String userId,
    Map<String, Object> fields,
    LogError error
) {
    public LogRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public record LogError(String type, String msg, String stack, String hash) {}
}
