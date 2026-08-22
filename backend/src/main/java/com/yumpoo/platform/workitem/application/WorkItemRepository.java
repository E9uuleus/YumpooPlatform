package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.workitem.domain.WorkItem;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WorkItemRepository {
    long nextSequence(UUID companyId, UUID projectId);
    boolean insert(WorkItem workItem);
    Optional<WorkItemModels.WorkItemLocator> findLocator(UUID companyId, UUID workItemId);
    Optional<WorkItem> find(UUID companyId, UUID projectId, UUID contentId, UUID workItemId);
    List<WorkItem> findPage(UUID companyId, UUID projectId, UUID contentId,
            Set<String> statuses, OffsetPageRequest page);
    long countPage(UUID companyId, UUID projectId, UUID contentId, Set<String> statuses);
    long countOpenByProject(UUID companyId, UUID projectId);
    long countOpenByContent(UUID companyId, UUID projectId, UUID contentId);
}
