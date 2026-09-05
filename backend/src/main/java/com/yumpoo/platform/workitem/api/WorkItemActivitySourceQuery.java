package com.yumpoo.platform.workitem.api;

import java.util.Optional;
import java.util.UUID;

public interface WorkItemActivitySourceQuery {
    Optional<WorkItemActivityReference> findIncludingDeleted(UUID companyId, UUID workItemId);
    Optional<WorkItemActivityReference> findAttachmentOwner(UUID companyId, String ownerType,
            UUID ownerId);
    Optional<ContentActivityReference> findContent(UUID companyId, UUID projectId, UUID contentId);
    Optional<LabelActivityReference> findStatus(UUID companyId, UUID projectId, String code);
    Optional<LabelActivityReference> findPriority(UUID companyId, UUID projectId, String code);

    record WorkItemActivityReference(UUID id, UUID projectId, UUID contentId,
            String itemNo, String title) {
    }

    record ContentActivityReference(UUID id, String displayName) {}
    record LabelActivityReference(String code, String displayName, String colorToken) {}
}
