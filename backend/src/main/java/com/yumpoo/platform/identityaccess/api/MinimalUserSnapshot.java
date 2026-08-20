package com.yumpoo.platform.identityaccess.api;

import java.util.Objects;
import java.util.UUID;

public record MinimalUserSnapshot(
        UUID userId,
        UUID companyId,
        String displayName,
        String employmentStatus,
        String accountStatus
) {
    public MinimalUserSnapshot {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(employmentStatus, "employmentStatus must not be null");
        Objects.requireNonNull(accountStatus, "accountStatus must not be null");
    }

    public boolean activeAndEnabled() {
        return "ACTIVE".equals(employmentStatus) && "ENABLED".equals(accountStatus);
    }
}
