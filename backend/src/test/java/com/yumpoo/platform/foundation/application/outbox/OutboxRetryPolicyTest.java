package com.yumpoo.platform.foundation.application.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRetryPolicyTest {

    @Test
    void fiveRetryWindowsAreStableAndTheSixthFailureIsTerminal() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(() -> 0.0);
        Instant failedAt = Instant.parse("2026-08-10T03:00:00Z");
        Duration[] expected = {
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                Duration.ofHours(2),
                Duration.ofHours(8)
        };

        for (int attempt = 1; attempt <= expected.length; attempt++) {
            assertThat(policy.shouldRetry(attempt, true)).isTrue();
            assertThat(policy.nextAttemptAt(attempt, failedAt))
                    .isEqualTo(failedAt.plus(expected[attempt - 1]));
        }
        assertThat(policy.shouldRetry(6, true)).isFalse();
        assertThat(policy.shouldRetry(1, false)).isFalse();
    }

    @Test
    void positiveJitterNeverShortensTheBaseDelay() {
        OutboxRetryPolicy policy = new OutboxRetryPolicy(() -> 0.099);
        Instant failedAt = Instant.parse("2026-08-10T03:00:00Z");

        Instant next = policy.nextAttemptAt(1, failedAt);

        assertThat(next).isAfterOrEqualTo(failedAt.plus(Duration.ofMinutes(1)));
        assertThat(next).isBefore(failedAt.plusSeconds(66));
    }
}
