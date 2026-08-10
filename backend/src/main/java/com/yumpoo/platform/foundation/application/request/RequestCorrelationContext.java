package com.yumpoo.platform.foundation.application.request;

import java.util.Objects;
import java.util.Optional;

/**
 * 将 HTTP 或 Outbox 消费边界建立的关联信息限定在线程作用域内。
 */
public final class RequestCorrelationContext {

    private static final ThreadLocal<RequestCorrelation> CURRENT = new ThreadLocal<>();

    private RequestCorrelationContext() {
    }

    public static Optional<RequestCorrelation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static RequestCorrelation required() {
        return current().orElseThrow(() -> new IllegalStateException(
                "request correlation context is required"
        ));
    }

    public static Scope open(RequestCorrelation correlation) {
        Objects.requireNonNull(correlation, "correlation must not be null");
        RequestCorrelation previous = CURRENT.get();
        CURRENT.set(correlation);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {

        private final RequestCorrelation previous;
        private boolean closed;

        private Scope(RequestCorrelation previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
