package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.authentication.WebLoginCompletionService;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;

import java.util.Objects;
import java.util.Optional;

final class LocalSessionIssuer {

    private final LocalAuthenticationProperties properties;
    private final WebLoginCompletionService completionService;

    LocalSessionIssuer(
            LocalAuthenticationProperties properties,
            WebLoginCompletionService completionService
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.completionService = Objects.requireNonNull(
                completionService,
                "completionService must not be null"
        );
    }

    Optional<IssuedSession> issue() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(completionService.complete(properties.getMemberId()));
    }
}
