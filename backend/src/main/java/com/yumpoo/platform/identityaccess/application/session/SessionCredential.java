package com.yumpoo.platform.identityaccess.application.session;

import java.util.Objects;

public record SessionCredential(String value) {

    public SessionCredential {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("credential is invalid");
        }
    }

    @Override
    public String toString() {
        return "SessionCredential[value=REDACTED]";
    }
}
