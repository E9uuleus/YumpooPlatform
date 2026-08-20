package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MinimalUserSnapshotQuery {
    Map<UUID, MinimalUserSnapshot> findByUserIds(UUID companyId, Collection<UUID> userIds);
    Optional<MinimalUserSnapshot> findByUserId(UUID companyId, UUID userId);
    MinimalUserPage findActiveEnabledByName(UUID companyId, String name, OffsetPageRequest page);
}
