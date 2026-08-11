package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.request.RequestIdContext;

import java.time.Instant;
import java.util.Objects;

public record DesktopAuthAttempt(
        DesktopAuthTokenHash desktopStateHash,
        DesktopAuthTokenHash oauthStateHash,
        PkceS256Challenge pkceChallenge,
        String requestId,
        Instant createdAt,
        Instant authorizeExpiresAt
) {

    public DesktopAuthAttempt {
        Objects.requireNonNull(desktopStateHash, "desktopStateHash must not be null");
        Objects.requireNonNull(oauthStateHash, "oauthStateHash must not be null");
        Objects.requireNonNull(pkceChallenge, "pkceChallenge must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(authorizeExpiresAt, "authorizeExpiresAt must not be null");
        if (desktopStateHash.equals(oauthStateHash)) {
            throw new IllegalArgumentException("desktop and OAuth state hashes must be independent");
        }
        if (!RequestIdContext.isValid(requestId)) {
            throw new IllegalArgumentException("requestId has an invalid format");
        }
        if (!authorizeExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("authorizeExpiresAt must be after createdAt");
        }
    }
}
