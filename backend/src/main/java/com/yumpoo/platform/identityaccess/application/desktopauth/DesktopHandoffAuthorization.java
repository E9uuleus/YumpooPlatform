package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.time.Instant;
import java.util.Objects;

public record DesktopHandoffAuthorization(
        DesktopAuthToken handoffCode,
        DesktopAuthToken desktopState,
        Instant expiresAt
) {

    public DesktopHandoffAuthorization {
        Objects.requireNonNull(handoffCode, "handoffCode must not be null");
        Objects.requireNonNull(desktopState, "desktopState must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "DesktopHandoffAuthorization[expiresAt=" + expiresAt + ", credentials=REDACTED]";
    }
}
