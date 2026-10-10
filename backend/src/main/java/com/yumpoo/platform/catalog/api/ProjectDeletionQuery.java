package com.yumpoo.platform.catalog.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProjectDeletionQuery {
    record State(UUID projectId, UUID companyId, String code, String lifecycle, UUID ownerUserId,
            long rowVersion, Instant requestedAt, UUID requestedBy, Instant purgeAfter, Instant purgeStartedAt) {}
    Optional<State> find(UUID companyId, UUID projectId);
    Optional<State> lockForProjection(UUID companyId, UUID projectId);
    boolean purgingOrPurged(UUID companyId, UUID projectId);
}
