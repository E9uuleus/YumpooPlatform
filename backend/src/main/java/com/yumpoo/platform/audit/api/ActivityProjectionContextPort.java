package com.yumpoo.platform.audit.api;

import java.util.Optional;
import java.util.UUID;

public interface ActivityProjectionContextPort {
    Optional<String> userDisplayName(UUID companyId, UUID userId);
    Optional<WorkItemReference> workItem(UUID companyId, UUID workItemId);
    Optional<WorkItemReference> attachmentOwnerWorkItem(UUID companyId, String ownerType,
            UUID ownerId);

    record WorkItemReference(UUID id, UUID projectId, String itemNo, String title) {
        public String displayRef() {
            return itemNo + " " + title;
        }
    }
}
