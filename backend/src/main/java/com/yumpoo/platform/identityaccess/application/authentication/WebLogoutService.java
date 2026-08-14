package com.yumpoo.platform.identityaccess.application.authentication;

import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebLogoutService {

    private final SessionService sessionService;
    private final AuthenticationEventService eventService;

    public WebLogoutService(
            SessionService sessionService,
            AuthenticationEventService eventService
    ) {
        this.sessionService = sessionService;
        this.eventService = eventService;
    }

    @Transactional
    public void logout(AuthenticatedSession authenticatedSession) {
        if (sessionService.logout(authenticatedSession)) {
            eventService.userSessionsRevoked(authenticatedSession);
        }
    }
}
