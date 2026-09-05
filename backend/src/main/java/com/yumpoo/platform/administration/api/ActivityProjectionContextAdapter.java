package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.audit.api.ActivityProjectionContextPort;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
import com.yumpoo.platform.workitem.api.WorkItemActivitySourceQuery;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ActivityProjectionContextAdapter implements ActivityProjectionContextPort {
    private final MinimalUserSnapshotQuery users;
    private final WorkItemActivitySourceQuery workItems;

    public ActivityProjectionContextAdapter(MinimalUserSnapshotQuery users,
            WorkItemActivitySourceQuery workItems) {
        this.users = users;
        this.workItems = workItems;
    }

    @Override
    public Optional<String> userDisplayName(UUID companyId, UUID userId) {
        return users.findByUserId(companyId, userId).map(user -> user.displayName());
    }

    @Override
    public Optional<WorkItemReference> workItem(UUID companyId, UUID workItemId) {
        return workItems.findIncludingDeleted(companyId, workItemId).map(ActivityProjectionContextAdapter::map);
    }

    @Override
    public Optional<WorkItemReference> attachmentOwnerWorkItem(UUID companyId, String ownerType,
            UUID ownerId) {
        return workItems.findAttachmentOwner(companyId, ownerType, ownerId)
                .map(ActivityProjectionContextAdapter::map);
    }

    @Override
    public Optional<ContentReference> content(UUID companyId, UUID projectId, UUID contentId) {
        return workItems.findContent(companyId, projectId, contentId)
                .map(value -> new ContentReference(value.id(), value.displayName()));
    }

    @Override
    public Optional<LabelReference> status(UUID companyId, UUID projectId, String code) {
        return workItems.findStatus(companyId, projectId, code)
                .map(value -> new LabelReference(value.code(), value.displayName(), value.colorToken()));
    }

    @Override
    public Optional<LabelReference> priority(UUID companyId, UUID projectId, String code) {
        return workItems.findPriority(companyId, projectId, code)
                .map(value -> new LabelReference(value.code(), value.displayName(), value.colorToken()));
    }

    private static WorkItemReference map(
            WorkItemActivitySourceQuery.WorkItemActivityReference reference) {
        return new WorkItemReference(reference.id(), reference.projectId(), reference.itemNo(),
                reference.title());
    }
}
