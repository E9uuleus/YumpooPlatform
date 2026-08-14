package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.UUID;

public record CurrentActor(UUID userId, UUID companyId, long authorizationVersion) {

    public CurrentActor {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (authorizationVersion < 0) {
            throw new IllegalArgumentException("authorizationVersion must not be negative");
        }
    }
}
