package com.yumpoo.platform.audit.api;

import java.util.Optional;
import java.util.UUID;

public interface ActivityProjectionContextPort {
    Optional<String> userDisplayName(UUID companyId, UUID userId);
    Optional<WorkItemReference> workItem(UUID companyId, UUID workItemId);
    Optional<WorkItemReference> attachmentOwnerWorkItem(UUID companyId, String ownerType,
            UUID ownerId);
    Optional<ContentReference> content(UUID companyId, UUID projectId, UUID contentId);
    Optional<LabelReference> status(UUID companyId, UUID projectId, String code);
    Optional<LabelReference> priority(UUID companyId, UUID projectId, String code);

    record WorkItemReference(UUID id, UUID projectId, String itemNo, String title) {
        public String displayRef() {
            return itemNo + " " + title;
        }
    }
    record ContentReference(UUID id, String displayName) {}
    record LabelReference(String code, String displayName, String colorToken) {}
}
