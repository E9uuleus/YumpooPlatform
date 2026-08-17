package com.yumpoo.platform.identityaccess.application.desktopauth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProductDesktopAuthExchange(
        UUID userId,
        String clientVersion,
        String clientProtocolVersion,
        Instant handoffIssuedAt,
        Instant consumedAt
) {
    public ProductDesktopAuthExchange {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(clientVersion, "clientVersion must not be null");
        Objects.requireNonNull(clientProtocolVersion, "clientProtocolVersion must not be null");
        Objects.requireNonNull(handoffIssuedAt, "handoffIssuedAt must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
    }
}
