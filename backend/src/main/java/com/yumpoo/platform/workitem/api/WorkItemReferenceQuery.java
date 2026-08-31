package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Optional;
import java.util.UUID;

public interface WorkItemReferenceQuery {
    Optional<WorkItemReferenceSnapshot> findVisible(CurrentActor actor, UUID workItemId);
    Optional<WorkItemReferenceSnapshot> findVisibleIncludingDeleted(CurrentActor actor,
                                                                     UUID workItemId);
}
