package com.yumpoo.platform.identityaccess.application.desktopauth;

import com.yumpoo.platform.foundation.application.request.RequestIdContext;

import java.time.Instant;
import java.util.Objects;

public record ProductDesktopAuthAttempt(
        DesktopAuthTokenHash stateHash,
        PkceS256Challenge pkceChallenge,
        String requestId,
        String clientVersion,
        String clientProtocolVersion,
        Instant createdAt,
        Instant authorizeExpiresAt
) {
    public ProductDesktopAuthAttempt {
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        Objects.requireNonNull(pkceChallenge, "pkceChallenge must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(authorizeExpiresAt, "authorizeExpiresAt must not be null");
        if (!RequestIdContext.isValid(requestId)
                || clientVersion == null || !clientVersion.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
                || clientVersion.length() > 32
                || !"1".equals(clientProtocolVersion)
                || !authorizeExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("product desktop attempt is invalid");
        }
    }
}
