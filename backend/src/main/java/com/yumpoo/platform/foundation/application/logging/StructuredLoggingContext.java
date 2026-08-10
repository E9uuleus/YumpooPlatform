package com.yumpoo.platform.foundation.application.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对 MDC 字段做可嵌套的保存与恢复，避免线程池复用造成关联信息串线。
 */
public final class StructuredLoggingContext {

    public static final String REQUEST_ID = "requestId";
    public static final String CORRELATION_ID = "correlationId";
    public static final String EVENT_ID = "eventId";
    public static final String CONSUMER_NAME = "consumerName";
    public static final String ATTEMPT = "attempt";
    public static final String OUTCOME = "outcome";
    public static final String ERROR_CODE = "errorCode";

    private StructuredLoggingContext() {
    }

    public static Scope open(Map<String, ?> values) {
        Map<String, String> previous = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            previous.put(key, MDC.get(key));
            Object value = entry.getValue();
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, String.valueOf(value));
            }
        }
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;
        private boolean closed;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) {
                    MDC.remove(entry.getKey());
                } else {
                    MDC.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
