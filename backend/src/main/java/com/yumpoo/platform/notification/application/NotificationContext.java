package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface NotificationContext {
    record Participants(UUID projectId, UUID assigneeUserId, UUID reporterUserId) {}
    record Update(UUID projectId, UUID workItemId, UUID authorUserId, UUID parentAuthorUserId) {}
    record Reference(UUID id, NotificationModels.TargetKind kind, UUID projectId,
            UUID workItemId, UUID updateId) {}
    record RenderRequest(Collection<Reference> references, Collection<UUID> userIds) {}
    record Rendered(Map<UUID, NotificationModels.Target> targets,
            Map<UUID, NotificationModels.Person> people) {}
    Optional<Participants> workItemParticipants(UUID companyId, UUID workItemId);
    Optional<Update> update(UUID companyId, UUID updateId);
    Optional<UUID> projectOwner(UUID companyId, UUID projectId);
    Set<UUID> eligibleProjectRecipients(UUID companyId, UUID projectId, Collection<UUID> users);
    Set<UUID> activeAccounts(UUID companyId, Collection<UUID> users);
    Rendered render(CurrentActor actor, RenderRequest request);
}
