package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.application.WorkItemModels.WorkItemLocator;
import com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateLocator;
import com.yumpoo.platform.workitem.domain.WorkItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WorkItemActivitySourceService {
    private final WorkItemRepository workItems;
    private final WorkItemUpdateRepository updates;

    public WorkItemActivitySourceService(WorkItemRepository workItems,
            WorkItemUpdateRepository updates) {
        this.workItems = workItems;
        this.updates = updates;
    }

    @Transactional(readOnly = true)
    public Optional<Reference> findIncludingDeleted(UUID companyId, UUID workItemId) {
        return workItems.findLocatorIncludingDeleted(companyId, workItemId)
                .flatMap(locator -> find(companyId, locator));
    }

    @Transactional(readOnly = true)
    public Optional<Reference> findAttachmentOwner(UUID companyId, String ownerType,
            UUID ownerId) {
        if ("WORK_ITEM".equals(ownerType)) return findIncludingDeleted(companyId, ownerId);
        if (!"WORK_ITEM_UPDATE".equals(ownerType)) return Optional.empty();
        return updates.findLocator(companyId, ownerId).flatMap(locator -> find(companyId, locator));
    }

    private Optional<Reference> find(UUID companyId, WorkItemLocator locator) {
        return workItems.findIncludingDeleted(companyId, locator.projectId(), locator.contentId(),
                        locator.workItemId()).map(WorkItemActivitySourceService::reference);
    }

    private Optional<Reference> find(UUID companyId, UpdateLocator locator) {
        return workItems.findIncludingDeleted(companyId, locator.projectId(), locator.contentId(),
                        locator.workItemId()).map(WorkItemActivitySourceService::reference);
    }

    private static Reference reference(WorkItem item) {
        return new Reference(item.id(), item.projectId(), item.contentId(), item.itemNo(),
                item.title());
    }

    public record Reference(UUID id, UUID projectId, UUID contentId, String itemNo, String title) {
    }
}
