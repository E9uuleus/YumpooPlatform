package com.yumpoo.platform.identityaccess.application.directory;

import java.util.UUID;
import java.util.Objects;

public record DirectorySyncClaim(
        DirectorySyncRunSnapshot snapshot,
        UUID leaseToken,
        DirectorySyncClaimDisposition disposition
) {
    public DirectorySyncClaim {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        if ((disposition == DirectorySyncClaimDisposition.NEW) != (leaseToken != null)) {
            throw new IllegalArgumentException("only the execution owner may receive a lease token");
        }
    }

    public DirectorySyncClaim(
            DirectorySyncRunSnapshot snapshot,
            UUID leaseToken,
            boolean executionOwner
    ) {
        this(
                snapshot,
                leaseToken,
                executionOwner
                        ? DirectorySyncClaimDisposition.NEW
                        : DirectorySyncClaimDisposition.REPLAY
        );
    }

    public boolean executionOwner() {
        return disposition == DirectorySyncClaimDisposition.NEW;
    }
}
