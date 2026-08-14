package com.yumpoo.platform.identityaccess.api;

import java.util.Set;
import java.util.UUID;

public interface PlatformRoleQuery {

    Set<PlatformRoleCode> findActiveRoleCodes(UUID companyId, UUID userId);
}
