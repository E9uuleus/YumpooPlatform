package com.yumpoo.platform.identityaccess.application.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountStatusCommandActor(
        UUID userId,
        long sessionAuthorizationVersion,
        Instant authenticatedAt
) {
    public AccountStatusCommandActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
        if (sessionAuthorizationVersion < 0) {
            throw new IllegalArgumentException("sessionAuthorizationVersion must not be negative");
        }
    }
}
