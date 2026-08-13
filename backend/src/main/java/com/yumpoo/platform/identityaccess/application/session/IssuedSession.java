package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.session.LoginSession;

import java.util.Objects;

public record IssuedSession(
        LoginSession session,
        SessionCredential sessionCredential,
        SessionCredential csrfCredential
) {

    public IssuedSession {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(sessionCredential, "sessionCredential must not be null");
        Objects.requireNonNull(csrfCredential, "csrfCredential must not be null");
    }

    @Override
    public String toString() {
        return "IssuedSession[session=" + session.id() + ", credentials=REDACTED]";
    }
}
