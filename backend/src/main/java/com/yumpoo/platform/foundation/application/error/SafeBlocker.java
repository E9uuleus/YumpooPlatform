package com.yumpoo.platform.foundation.application.error;

import java.util.Objects;

public record SafeBlocker(String code, long count) {

    public SafeBlocker {
        Objects.requireNonNull(code, "code must not be null");
        if (!code.matches("^[A-Z][A-Z0-9_]{1,79}$")) {
            throw new IllegalArgumentException("code must be a stable uppercase code");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
    }
}
