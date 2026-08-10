package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * HTTP 幂等键的持久化作用域。
 */
public record IdempotencyScope(
        UUID actorUserId,
        String httpMethod,
        String routeKey,
        UUID idempotencyKey
) {

    private static final int MAX_HTTP_METHOD_LENGTH = 10;
    private static final int MAX_ROUTE_KEY_LENGTH = 160;

    public IdempotencyScope {
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(routeKey, "routeKey must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        httpMethod = httpMethod.strip().toUpperCase(Locale.ROOT);
        routeKey = routeKey.strip();
        if (httpMethod.isEmpty() || httpMethod.length() > MAX_HTTP_METHOD_LENGTH) {
            throw new IllegalArgumentException("httpMethod length must be between 1 and 10");
        }
        if (routeKey.isEmpty() || routeKey.length() > MAX_ROUTE_KEY_LENGTH) {
            throw new IllegalArgumentException("routeKey length must be between 1 and 160");
        }
    }
}
