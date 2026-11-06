package com.yumpoo.platform.reporting.application;

import static com.yumpoo.platform.reporting.application.DashboardModels.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardRepository {
    List<Stored> list(UUID companyId, UUID ownerUserId);

    Optional<Stored> find(UUID companyId, UUID ownerUserId, UUID id, boolean includeDeleted);

    void insert(Stored value);

    boolean update(Stored value, long expectedVersion);

    boolean delete(UUID companyId, UUID ownerUserId, UUID id, long expectedVersion, Instant now);
}
