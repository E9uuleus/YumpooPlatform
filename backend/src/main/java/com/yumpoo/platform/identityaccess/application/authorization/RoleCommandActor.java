package com.yumpoo.platform.identityaccess.application.authorization;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoleCommandActor(
        UUID userId,
        long sessionAuthorizationVersion,
        Instant authenticatedAt
) {
    public RoleCommandActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        if (sessionAuthorizationVersion < 0) {
            throw new IllegalArgumentException("sessionAuthorizationVersion must not be negative");
        }
    }
}
