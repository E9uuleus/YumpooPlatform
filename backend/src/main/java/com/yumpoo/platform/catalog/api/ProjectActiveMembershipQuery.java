package com.yumpoo.platform.catalog.api;

import java.util.UUID;

public interface ProjectActiveMembershipQuery {
    boolean isActiveMember(UUID companyId, UUID projectId, UUID userId);
}
