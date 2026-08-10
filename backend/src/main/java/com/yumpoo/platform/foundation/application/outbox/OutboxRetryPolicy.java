package com.yumpoo.platform.foundation.application.outbox;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Component
public class OutboxRetryPolicy {

    private static final List<Duration> BASE_DELAYS = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            Duration.ofHours(8)
    );

    private final DoubleSupplier jitterSupplier;

    public OutboxRetryPolicy() {
        this(() -> ThreadLocalRandom.current().nextDouble(0.1));
    }

    OutboxRetryPolicy(DoubleSupplier jitterSupplier) {
        this.jitterSupplier = jitterSupplier;
    }

    public boolean shouldRetry(int attemptCount, boolean retryable) {
        return retryable && attemptCount >= 1 && attemptCount <= BASE_DELAYS.size();
    }

    public Instant nextAttemptAt(int attemptCount, Instant failedAt) {
        if (attemptCount < 1 || attemptCount > BASE_DELAYS.size()) {
            throw new IllegalArgumentException("attemptCount has no retry delay: " + attemptCount);
        }
        double jitter = jitterSupplier.getAsDouble();
        if (jitter < 0 || jitter >= 0.1) {
            throw new IllegalStateException("retry jitter must be between 0 inclusive and 0.1 exclusive");
        }
        long baseMillis = BASE_DELAYS.get(attemptCount - 1).toMillis();
        long jitterMillis = (long) Math.floor(baseMillis * jitter);
        return failedAt.plusMillis(Math.addExact(baseMillis, jitterMillis));
    }
}
