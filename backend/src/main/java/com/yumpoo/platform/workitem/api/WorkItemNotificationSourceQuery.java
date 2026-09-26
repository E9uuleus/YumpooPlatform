package com.yumpoo.platform.workitem.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WorkItemNotificationSourceQuery {
    record Participants(UUID projectId, UUID assigneeUserId, UUID reporterUserId) {}
    record Update(UUID projectId, UUID workItemId, UUID authorUserId, UUID parentAuthorUserId) {}
    Optional<Participants> findParticipants(UUID companyId, UUID itemId);
    Optional<Update> findUpdate(UUID companyId, UUID updateId);
    Map<UUID,String> findVisibleUpdateExcerpts(CurrentActor actor, Collection<UUID> ids);
}
