package com.yumpoo.platform.operations.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Component;

@Component
public class OperationsAccessPolicy {

    private final CurrentActorProvider actors;

    public OperationsAccessPolicy(CurrentActorProvider actors) {
        this.actors = actors;
    }

    public CurrentActor requireManager() {
        CurrentActor actor = actors.requiredActive();
        if (!actor.hasRole(PlatformRoleCode.APP_MANAGER)) throw new ApplicationException(
            StandardErrorCode.ACCESS_DENIED
        );
        return actor;
    }
}
