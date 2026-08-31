package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.workitem.application.WorkItemReferenceService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkItemReferenceAdapter implements WorkItemReferenceQuery {
    private final WorkItemReferenceService service;

    public WorkItemReferenceAdapter(WorkItemReferenceService service) {
        this.service = service;
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
                item.itemNo(), item.type(), item.title(), item.statusCode(),
                item.statusCategory(), item.deleted());
    }
}
