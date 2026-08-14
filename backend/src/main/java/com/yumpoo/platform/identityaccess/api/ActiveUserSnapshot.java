package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.UUID;

public record ActiveUserSnapshot(
        UUID userId,
        UUID companyId,
        boolean employmentActive,
        boolean accountEnabled,
        long authorizationVersion
) {

    public ActiveUserSnapshot {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (authorizationVersion < 0) {
            throw new IllegalArgumentException("authorizationVersion must not be negative");
        }
    }

    public boolean activeAndEnabled() {
        return employmentActive && accountEnabled;
    }
}
