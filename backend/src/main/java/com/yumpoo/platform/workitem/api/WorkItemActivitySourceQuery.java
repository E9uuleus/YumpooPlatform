package com.yumpoo.platform.workitem.api;

import java.util.Optional;
import java.util.UUID;

public interface WorkItemActivitySourceQuery {
    Optional<WorkItemActivityReference> findIncludingDeleted(UUID companyId, UUID workItemId);
    Optional<WorkItemActivityReference> findAttachmentOwner(UUID companyId, String ownerType,
            UUID ownerId);

    record WorkItemActivityReference(UUID id, UUID projectId, UUID contentId,
            String itemNo, String title) {
    }
}
