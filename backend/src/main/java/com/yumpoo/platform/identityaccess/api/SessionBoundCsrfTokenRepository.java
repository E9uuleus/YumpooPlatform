package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionCredential;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.session.SecureSessionCredentialGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.time.Clock;
import java.time.Duration;

final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

    static final String HEADER_NAME = "X-XSRF-TOKEN";
    private static final String PARAMETER_NAME = "_csrf";

    private final SessionService sessionService;
    private final Clock clock;
    private final SecureSessionCredentialGenerator unboundTokenGenerator =
            new SecureSessionCredentialGenerator();

    SessionBoundCsrfTokenRepository(SessionService sessionService, Clock clock) {
        this.sessionService = sessionService;
        this.clock = clock;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        if (!SessionRequestContext.present(request)) {
            return token(unboundTokenGenerator.generate().value());
        }
        AuthenticatedSession authenticated = SessionRequestContext.required(request);
        SessionCredential credential = sessionService.repairCsrf(authenticated);
        return token(credential.value());
    }

    @Override
    public void saveToken(
            CsrfToken token,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (!SessionRequestContext.present(request)) {
            return;
        }
        if (token == null) {
            response.addHeader(HttpHeaders.SET_COOKIE, SessionHttpCookies.clearCsrf().toString());
            return;
        }
        AuthenticatedSession authenticated = SessionRequestContext.required(request);
        Duration remaining = Duration.between(
                clock.instant(),
                authenticated.absoluteExpiresAt()
        );
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                SessionHttpCookies.csrf(token.getToken(), remaining).toString()
        );
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        AuthenticatedSession authenticated;
        try {
            authenticated = SessionRequestContext.required(request);
        } catch (IllegalStateException exception) {
            return null;
        }
        String value;
        try {
            value = SessionHttpCookies.single(request, SessionHttpCookies.CSRF_COOKIE)
                    .orElse(null);
            if (value == null) {
                return null;
            }
            SessionCredential credential = new SessionCredential(value);
            return sessionService.verifyCsrf(authenticated, credential) ? token(value) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static CsrfToken token(String value) {
        return new DefaultCsrfToken(HEADER_NAME, PARAMETER_NAME, value);
    }
}
