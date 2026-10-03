package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProjectConnectionTargetRepository {
    record Target(UUID projectId, String code, String name, String lifecycle) {}

    List<Target> searchActive(UUID companyId, String query, OffsetPageRequest page);
    long countActive(UUID companyId, String query);
    List<Target> findByIds(UUID companyId, Collection<UUID> ids);
}
