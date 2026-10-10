package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectConnectionTargetRepository.Target;
import com.yumpoo.platform.catalog.application.project.ProjectConnectionTargetService;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ProjectConnectionTargetAdapter implements ProjectConnectionTargetQuery {
    private final ProjectConnectionTargetService service;

    public ProjectConnectionTargetAdapter(ProjectConnectionTargetService service) { this.service = service; }

    @Override
    public ConnectTargetProjectPage searchActive(UUID companyId, String query, OffsetPageRequest page) {
        var result = service.searchActive(companyId, query, page);
        return new ConnectTargetProjectPage(result.items().stream()
                .map(target -> new ConnectTargetProject(target.projectId(), target.code(), target.name()))
                .toList(), result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Override
    public Map<UUID, ConnectTargetProjectSnapshot> findByIds(UUID companyId, Collection<UUID> ids) {
        return service.findByIds(companyId, ids).stream().map(ProjectConnectionTargetAdapter::snapshot)
                .collect(Collectors.toUnmodifiableMap(ConnectTargetProjectSnapshot::projectId, value -> value));
    }

    @Override
    public ConnectTargetProjectSnapshot lockAsConnectionTarget(UUID companyId, UUID projectId) {
        return snapshot(service.lockAsConnectionTarget(companyId, projectId));
    }

    private static ConnectTargetProjectSnapshot snapshot(Target target) {
        return new ConnectTargetProjectSnapshot(target.projectId(), target.code(), target.name(),
                ProjectAccessSnapshot.ProjectLifecycle.valueOf(target.lifecycle()),target.purging());
    }
}
