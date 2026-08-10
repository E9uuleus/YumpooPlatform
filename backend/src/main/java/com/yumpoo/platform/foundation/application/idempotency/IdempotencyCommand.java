package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Objects;

public record IdempotencyCommand(IdempotencyScope scope, RequestHash requestHash) {

    public IdempotencyCommand {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(requestHash, "requestHash must not be null");
    }
}
