package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record ProductDesktopAuthorization(URI authorizationUri, Instant expiresAt) {
    public ProductDesktopAuthorization {
        Objects.requireNonNull(authorizationUri, "authorizationUri must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    @Override
    public String toString() {
        return "ProductDesktopAuthorization[expiresAt=" + expiresAt + ", credentials=REDACTED]";
    }
}
