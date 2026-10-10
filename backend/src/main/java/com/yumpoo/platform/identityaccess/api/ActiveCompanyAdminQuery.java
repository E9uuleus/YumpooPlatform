package com.yumpoo.platform.identityaccess.api;

import java.util.Set;
import java.util.UUID;

public interface ActiveCompanyAdminQuery {
    Set<UUID> findUserIds(UUID companyId);
}
