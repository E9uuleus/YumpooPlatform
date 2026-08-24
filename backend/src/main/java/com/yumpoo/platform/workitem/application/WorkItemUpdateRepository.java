package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.workitem.domain.WorkItemUpdate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemUpdateModels.UpdateCursor;

public interface WorkItemUpdateRepository {
    boolean insert(WorkItemUpdate update, Map<UUID, String> mentionedDisplayNames);

    List<WorkItemUpdate> findOlderWindow(UUID companyId, UUID workItemId,
            UpdateCursor before, int limit);
}
