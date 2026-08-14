package com.yumpoo.platform.catalog.domain.authorization;

import java.util.Objects;
import java.util.UUID;

public record ProjectAccessFacts(UUID companyId, boolean activeMember, boolean owner) {

    public ProjectAccessFacts {
        Objects.requireNonNull(companyId, "companyId must not be null");
        if (owner && !activeMember) {
            throw new IllegalArgumentException("owner must be an active member");
        }
    }
}
