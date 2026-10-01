package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProjectConnectionTargetQuery {
    record ConnectTargetProject(UUID id, String code, String name) {}
    record ConnectTargetProjectPage(List<ConnectTargetProject> items, int page, int size,
            long totalElements, int totalPages) {}
    record ConnectTargetProjectSnapshot(UUID projectId, String code, String name,
            ProjectAccessSnapshot.ProjectLifecycle lifecycle) {}

    ConnectTargetProjectPage searchActive(UUID companyId, String query, OffsetPageRequest page);
    Map<UUID, ConnectTargetProjectSnapshot> findByIds(UUID companyId, Collection<UUID> ids);
    ConnectTargetProjectSnapshot lockAsConnectionTarget(UUID companyId, UUID projectId);
}
