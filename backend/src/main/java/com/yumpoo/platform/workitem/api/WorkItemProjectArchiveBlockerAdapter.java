package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.WorkItemRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public final class WorkItemProjectArchiveBlockerAdapter implements WorkItemProjectArchiveBlockerPort {
    private final WorkItemRepository repository;

    public WorkItemProjectArchiveBlockerAdapter(WorkItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public long countOpen(UUID companyId, UUID projectId) {
        return repository.countOpenByProject(companyId, projectId);
    }
}
