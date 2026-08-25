package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.WorkItemUpdate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;
import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateLocator;

public interface WorkItemUpdateRepository {
    boolean insert(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames);

    List<WorkItemUpdate> findOlderWindow(UUID companyId, UUID workItemId,
            UpdateCursor before, int limit);

    Optional<UpdateLocator> findLocator(UUID companyId, UUID updateId);

    Optional<WorkItemUpdate> find(UUID companyId, UUID updateId);

    Optional<WorkItemUpdate> lock(UUID companyId, UUID updateId);

    Map<UUID, String> findMentionedDisplayNames(UUID companyId, UUID updateId);

    boolean update(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames,
            long expectedVersion);

    boolean delete(WorkItemUpdate update, long expectedVersion);
}
