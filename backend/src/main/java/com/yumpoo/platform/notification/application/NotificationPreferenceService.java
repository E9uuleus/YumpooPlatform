package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationPreferenceService {
    private final NotificationRepository repository;
    private final NotificationContext context;
    public NotificationPreferenceService(NotificationRepository repository,NotificationContext context) {
        this.repository=repository;this.context=context;
    }
    @Transactional(readOnly=true) public ProjectPreference get(CurrentActor actor,UUID projectId) {
        requireMember(actor,projectId);
        return repository.preference(actor.companyId(),projectId,actor.userId()).orElseGet(()->ProjectPreference.defaults(projectId));
    }
    @Transactional public ProjectPreference update(CurrentActor actor,UUID projectId,ProjectPreferenceUpdate update) {
        requireMember(actor,projectId);
        return repository.savePreference(actor.companyId(),projectId,actor.userId(),update);
    }
    private void requireMember(CurrentActor actor,UUID projectId) {
        if(!context.eligibleProjectRecipients(actor.companyId(),projectId,Set.of(actor.userId())).contains(actor.userId()))
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
    }
}
