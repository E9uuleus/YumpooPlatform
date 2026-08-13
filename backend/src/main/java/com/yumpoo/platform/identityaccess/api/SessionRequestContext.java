package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;

final class SessionRequestContext {

    static final String ATTRIBUTE = SessionRequestContext.class.getName() + ".authenticatedSession";

    private SessionRequestContext() {
    }

    static AuthenticatedSession required(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof AuthenticatedSession session) {
            return session;
        }
        throw new IllegalStateException("authenticated session is required");
    }

    static boolean present(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof AuthenticatedSession;
    }
}
