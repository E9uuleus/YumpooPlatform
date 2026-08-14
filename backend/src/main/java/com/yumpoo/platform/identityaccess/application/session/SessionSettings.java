package com.yumpoo.platform.identityaccess.application.session;

import java.time.Duration;
import java.util.Objects;

public record SessionSettings(
        Duration idleTimeout,
        Duration absoluteTimeout,
        Duration revokedRetention,
        int purgeBatchSize,
        int purgeMaxBatches
) {

    public SessionSettings {
        Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
        Objects.requireNonNull(absoluteTimeout, "absoluteTimeout must not be null");
        Objects.requireNonNull(revokedRetention, "revokedRetention must not be null");
        if (idleTimeout.isNegative() || idleTimeout.isZero()
                || absoluteTimeout.compareTo(idleTimeout) < 0
                || revokedRetention.isNegative() || revokedRetention.isZero()
                || purgeBatchSize < 1 || purgeBatchSize > 5_000
                || purgeMaxBatches < 1 || purgeMaxBatches > 100) {
            throw new IllegalArgumentException("session settings are invalid");
        }
    }
}
