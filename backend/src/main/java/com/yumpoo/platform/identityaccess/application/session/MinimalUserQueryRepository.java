package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MinimalUserQueryRepository {
    List<MinimalUserRecord> findByUserIds(UUID companyId, Collection<UUID> userIds);
    MinimalUserRecordPage findActiveEnabledByName(UUID companyId, String name, OffsetPageRequest page);
}
