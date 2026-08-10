package com.yumpoo.platform.foundation.application.idempotency;

import java.util.Objects;

public record IdempotencyExecutionResult(StoredCommandResult result, boolean replayed) {

    public IdempotencyExecutionResult {
        Objects.requireNonNull(result, "result must not be null");
    }

    public static IdempotencyExecutionResult executed(StoredCommandResult result) {
        return new IdempotencyExecutionResult(result, false);
    }

    public static IdempotencyExecutionResult replayed(StoredCommandResult result) {
        return new IdempotencyExecutionResult(result, true);
    }
}
