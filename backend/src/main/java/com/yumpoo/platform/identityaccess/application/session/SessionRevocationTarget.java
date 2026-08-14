package com.yumpoo.platform.identityaccess.application.session;

import java.util.Objects;
import java.util.UUID;

public record SessionRevocationTarget(
        UUID userId,
        UUID companyId,
        long authorizationVersion,
        long aggregateVersion
) {

    public SessionRevocationTarget {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (authorizationVersion < 0 || aggregateVersion < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }
}
