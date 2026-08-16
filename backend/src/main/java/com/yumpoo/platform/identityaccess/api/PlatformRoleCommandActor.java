package com.yumpoo.platform.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlatformRoleCommandActor(
        UUID userId,
        long sessionAuthorizationVersion,
        Instant authenticatedAt
) {

    public PlatformRoleCommandActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        if (sessionAuthorizationVersion < 0) {
            throw new IllegalArgumentException("sessionAuthorizationVersion must not be negative");
        }
    }
}
