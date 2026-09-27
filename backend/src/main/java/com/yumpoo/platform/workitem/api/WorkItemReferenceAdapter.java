package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemReferenceService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkItemReferenceAdapter implements WorkItemReferenceQuery {
    private final WorkItemReferenceService service;
    private final com.yumpoo.platform.workitem.application.WorkItemNotificationSourceService notifications;

    public WorkItemReferenceAdapter(WorkItemReferenceService service,
            com.yumpoo.platform.workitem.application.WorkItemNotificationSourceService notifications) {
        this.service = service;
        this.notifications=notifications;
    }

    @Override public java.util.Map<UUID,WorkItemReferenceSnapshot> findVisible(CurrentActor actor, java.util.Collection<UUID> ids) {
        var result=new java.util.HashMap<UUID,WorkItemReferenceSnapshot>();
        notifications.references(actor,ids).forEach((id,item)->result.put(id,new WorkItemReferenceSnapshot(item.workItemId(),item.projectId(),
                item.contentId(),item.contentName(),item.contentColorToken(),item.itemNo(),item.title(),item.statusCode(),item.statusCategory(),item.deleted())));
        return java.util.Map.copyOf(result);
    }

    @Override
    public Optional<WorkItemReferenceSnapshot> findVisible(CurrentActor actor, UUID workItemId) {
        return service.findVisible(actor, workItemId, false).map(WorkItemReferenceAdapter::snapshot);
    }

    @Override
    public Optional<WorkItemReferenceSnapshot> findVisibleIncludingDeleted(CurrentActor actor,
                                                                            UUID workItemId) {
        return service.findVisible(actor, workItemId, true).map(WorkItemReferenceAdapter::snapshot);
    }

    private static WorkItemReferenceSnapshot snapshot(WorkItemReferenceService.Reference item) {
        return new WorkItemReferenceSnapshot(item.workItemId(), item.projectId(), item.contentId(),
                item.contentName(), item.contentColorToken(), item.itemNo(), item.title(), item.statusCode(),
                item.statusCategory(), item.deleted());
    }
}
