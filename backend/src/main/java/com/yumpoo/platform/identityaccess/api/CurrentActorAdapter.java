package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentActorAdapter implements CurrentActorProvider {

    private final ActiveUserSnapshotQuery userQuery;

    public CurrentActorAdapter(ActiveUserSnapshotQuery userQuery) {
        this.userQuery = userQuery;
    }

    @Override
    public CurrentActor requiredActive() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentActor actor)) {
            throw authenticationRequired();
        }
        ActiveUserSnapshot current = userQuery.findByUserId(actor.userId()).orElseThrow(
                CurrentActorAdapter::authenticationRequired
        );
        if (!current.activeAndEnabled()) {
            throw new ApplicationException(StandardErrorCode.ACCOUNT_DISABLED);
        }
        if (!current.companyId().equals(actor.companyId())
                || current.authorizationVersion() != actor.authorizationVersion()) {
            throw authenticationRequired();
        }
        return actor;
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
