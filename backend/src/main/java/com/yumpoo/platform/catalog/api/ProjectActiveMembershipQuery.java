package com.yumpoo.platform.catalog.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface ProjectActiveMembershipQuery {
    boolean isActiveMember(UUID companyId, UUID projectId, UUID userId);

    Set<UUID> findActiveMemberIds(UUID companyId, UUID projectId, Collection<UUID> userIds);
}
