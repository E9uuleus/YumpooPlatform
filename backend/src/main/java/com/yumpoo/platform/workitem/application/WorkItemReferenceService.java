package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.domain.WorkItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class WorkItemReferenceService {
    private final WorkItemRepository workItems;
    private final ContentRepository contents;
    private final ProjectAccessSnapshotQuery projectAccess;

    public WorkItemReferenceService(WorkItemRepository workItems, ContentRepository contents,
                                    ProjectAccessSnapshotQuery projectAccess) {
        this.workItems = workItems;
        this.contents = contents;
        this.projectAccess = projectAccess;
    }

    @Transactional(readOnly = true)
    public Optional<Reference> findVisible(CurrentActor actor, UUID workItemId,
                                           boolean includeDeleted) {
        if (actor == null) {
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        }
        Optional<WorkItemModels.WorkItemLocator> located = includeDeleted
                ? workItems.findLocatorIncludingDeleted(actor.companyId(), workItemId)
                : workItems.findLocator(actor.companyId(), workItemId);
        if (located.isEmpty()
                || projectAccess.findVisible(actor, located.get().projectId()).isEmpty()) {
            return Optional.empty();
        }
        WorkItemModels.WorkItemLocator locator = located.get();
        Optional<WorkItem> item = includeDeleted
                ? workItems.findIncludingDeleted(actor.companyId(), locator.projectId(),
                        locator.contentId(), workItemId)
                : workItems.find(actor.companyId(), locator.projectId(), locator.contentId(), workItemId);
        return item.map(value -> Reference.from(value, contents.find(actor.companyId(),
                locator.projectId(), locator.contentId()).orElse(null)));
    }

    public record Reference(UUID workItemId, UUID projectId, UUID contentId,
                            String contentName, String contentColorToken, String itemNo,
                            String title, String statusCode,
                            String statusCategory, boolean deleted) {
        private static Reference from(WorkItem item, com.yumpoo.platform.workitem.domain.Content content) {
            return new Reference(item.id(), item.projectId(), item.contentId(),
                    content == null ? "未知类别" : content.name(),
                    content == null ? "GRAY" : content.colorToken(), item.itemNo(),
                    item.title(), item.statusCode(),
                    item.statusCategory().name(), item.deletedAt() != null);
        }
    }
}
