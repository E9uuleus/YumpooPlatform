package com.yumpoo.platform.foundation.application.outbox;

import java.time.Duration;

public record OutboxRuntimeSettings(int batchSize, Duration leaseDuration) {

    public OutboxRuntimeSettings {
        if (batchSize <= 0 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
