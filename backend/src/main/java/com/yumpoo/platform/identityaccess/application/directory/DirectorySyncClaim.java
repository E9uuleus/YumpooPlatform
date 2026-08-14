package com.yumpoo.platform.identityaccess.application.directory;

import java.util.UUID;
import java.util.Objects;

public record DirectorySyncClaim(
        DirectorySyncRunSnapshot snapshot,
        UUID leaseToken,
        boolean executionOwner
) {
    public DirectorySyncClaim {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (executionOwner != (leaseToken != null)) {
            throw new IllegalArgumentException("only the execution owner may receive a lease token");
        }
    }
}
