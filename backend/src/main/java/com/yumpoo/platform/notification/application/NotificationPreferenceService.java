package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationPreferenceService {
    private final NotificationRepository repository;
    private final NotificationContext context;
    public NotificationPreferenceService(NotificationRepository repository,NotificationContext context) {
        this.repository=repository;this.context=context;
    }
    @Transactional public ProjectPreference get(CurrentActor actor,UUID projectId) {
        requireMember(actor,projectId);
        return repository.preference(actor.companyId(),projectId,actor.userId()).orElseGet(()->ProjectPreference.defaults(projectId));
    }
    @Transactional public ProjectPreference update(CurrentActor actor,UUID projectId,ProjectPreferenceUpdate update) {
        requireMember(actor,projectId);
        return repository.savePreference(actor.companyId(),projectId,actor.userId(),update);
    }
    private void requireMember(CurrentActor actor,UUID projectId) {
        // Hold the project through access checks and preference reads/writes, so purge cannot pass this transaction.
        if(context.projectOwner(actor.companyId(),projectId).isEmpty())
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        if(!context.eligibleProjectRecipients(actor.companyId(),projectId,Set.of(actor.userId())).contains(actor.userId()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        UUID referenceId=UUID.randomUUID();
        var rendered=context.render(actor,new NotificationContext.RenderRequest(
                List.of(new NotificationContext.Reference(referenceId,TargetKind.PROJECT,projectId,null,null)),Set.of()));
        if(!Optional.ofNullable(rendered.targets().get(referenceId)).map(Target::accessible).orElse(false))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }
}
