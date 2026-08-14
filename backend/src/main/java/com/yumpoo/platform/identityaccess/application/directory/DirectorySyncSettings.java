package com.yumpoo.platform.identityaccess.application.directory;

import java.time.Duration;
import java.util.Objects;

public record DirectorySyncSettings(int pageSize, Duration leaseDuration) {

    public DirectorySyncSettings {
        if (pageSize < 1 || pageSize > 10_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 10000");
        }
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
