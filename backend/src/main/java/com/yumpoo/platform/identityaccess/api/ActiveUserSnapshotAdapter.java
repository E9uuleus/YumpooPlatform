package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.session.UserAuthorizationQueryService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ActiveUserSnapshotAdapter implements ActiveUserSnapshotQuery {

    private final UserAuthorizationQueryService service;

    public ActiveUserSnapshotAdapter(UserAuthorizationQueryService service) {
        this.service = service;
    }

    @Override
    public Optional<ActiveUserSnapshot> findByUserId(UUID userId) {
        return service.findByUserId(userId).map(user -> new ActiveUserSnapshot(
                user.userId(),
                user.companyId(),
                user.employmentActive(),
                user.accountEnabled(),
                user.authorizationVersion()
        ));
    }
}
