package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.session.LoginSession;

import java.util.Objects;
import java.time.Instant;

public record AuthenticatedSession(
        LoginSession session,
        UserAuthorizationRecord user
) {

    public AuthenticatedSession {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(user, "user must not be null");
    }

    public Instant absoluteExpiresAt() {
        return session.absoluteExpiresAt();
    }

    public Instant authenticatedAt() {
        return session.issuedAt();
    }

    public String clientTypeCode() {
        return session.clientType().name();
    }

    public String clientVersion() {
        return session.clientVersion();
    }
}
