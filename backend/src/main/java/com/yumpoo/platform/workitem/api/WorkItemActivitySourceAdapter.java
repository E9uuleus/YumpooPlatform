package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.workitem.application.WorkItemActivitySourceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkItemActivitySourceAdapter implements WorkItemActivitySourceQuery {
    private final WorkItemActivitySourceService service;

    public WorkItemActivitySourceAdapter(WorkItemActivitySourceService service) {
        this.service = service;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemActivityReference> findIncludingDeleted(UUID companyId,
            UUID workItemId) {
        return service.findIncludingDeleted(companyId, workItemId)
                .map(WorkItemActivitySourceAdapter::reference);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkItemActivityReference> findAttachmentOwner(UUID companyId,
            String ownerType, UUID ownerId) {
        return service.findAttachmentOwner(companyId, ownerType, ownerId)
                .map(WorkItemActivitySourceAdapter::reference);
    }

    private static WorkItemActivityReference reference(
            WorkItemActivitySourceService.Reference item) {
        return new WorkItemActivityReference(item.id(), item.projectId(), item.contentId(),
                item.itemNo(), item.title());
    }
}
