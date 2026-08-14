package com.yumpoo.platform.identityaccess.application.authorization;

import java.util.Set;
import java.util.UUID;

public interface PlatformRoleRepository {

    Set<String> findActiveRoleCodes(UUID companyId, UUID userId);
}
