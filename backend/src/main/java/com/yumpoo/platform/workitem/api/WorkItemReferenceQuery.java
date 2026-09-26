package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Optional;
import java.util.UUID;

public interface WorkItemReferenceQuery {
    default java.util.Map<UUID,WorkItemReferenceSnapshot> findVisible(CurrentActor actor, java.util.Collection<UUID> ids) {
        var result=new java.util.HashMap<UUID,WorkItemReferenceSnapshot>();
        ids.forEach(id->findVisible(actor,id).ifPresent(item->result.put(id,item)));
        return java.util.Map.copyOf(result);
    }
    Optional<WorkItemReferenceSnapshot> findVisible(CurrentActor actor, UUID workItemId);
    Optional<WorkItemReferenceSnapshot> findVisibleIncludingDeleted(CurrentActor actor,
                                                                     UUID workItemId);
}
