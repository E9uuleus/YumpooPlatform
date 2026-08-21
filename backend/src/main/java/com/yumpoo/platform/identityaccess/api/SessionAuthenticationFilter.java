package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionCredential;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

final class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final PlatformRoleQuery platformRoleQuery;
    private final ApiErrorWriter errorWriter;
    private final LocalSessionIssuer localSessionIssuer;
    private final Clock clock;

    SessionAuthenticationFilter(
            SessionService sessionService,
            PlatformRoleQuery platformRoleQuery,
            ApiErrorWriter errorWriter,
            LocalSessionIssuer localSessionIssuer,
            Clock clock
    ) {
        this.sessionService = sessionService;
        this.platformRoleQuery = platformRoleQuery;
        this.errorWriter = errorWriter;
        this.localSessionIssuer = localSessionIssuer;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return WebAuthenticationPaths.AUTHORIZE.equals(path)
                || WebAuthenticationPaths.CALLBACK.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            Optional<String> raw = SessionHttpCookies.single(
                    request,
                    SessionHttpCookies.SESSION_COOKIE
            );
            boolean issuedLocally = raw.isEmpty();
            SessionCredential credential = issuedLocally
                    ? issueLocalSession(response)
                            .map(IssuedSession::sessionCredential)
                            .orElseThrow(SessionAuthenticationFilter::authenticationRequired)
                    : new SessionCredential(raw.orElseThrow());
            AuthenticatedSession authenticated;
            try {
                authenticated = authenticate(request, credential);
            } catch (ApplicationException exception) {
                if (issuedLocally
                        || exception.errorCode() != StandardErrorCode.AUTHENTICATION_REQUIRED) {
                    throw exception;
                }
                credential = issueLocalSession(response)
                        .map(IssuedSession::sessionCredential)
                        .orElseThrow(() -> exception);
                authenticated = authenticate(request, credential);
            }
            request.setAttribute(SessionRequestContext.ATTRIBUTE, authenticated);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new SessionAuthenticationToken(new CurrentActor(
                    authenticated.user().userId(),
                    authenticated.user().companyId(),
                    authenticated.user().authorizationVersion(),
                    platformRoleQuery.findActiveRoleCodes(
                            authenticated.user().companyId(),
                            authenticated.user().userId()
                    )
            )));
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
            if (response.getStatus() >= 200 && response.getStatus() < 400) {
                sessionService.touch(authenticated);
            }
        } catch (IllegalArgumentException exception) {
            clearSecurityCookies(response);
            reject(request, response, new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));
        } catch (ApplicationException exception) {
            if (exception.errorCode() == StandardErrorCode.AUTHENTICATION_REQUIRED) {
                clearSecurityCookies(response);
            }
            reject(request, response, exception);
        } catch (DataAccessResourceFailureException exception) {
            reject(request, response, new ApplicationException(StandardErrorCode.DEPENDENCY_UNAVAILABLE));
        } finally {
            request.removeAttribute(SessionRequestContext.ATTRIBUTE);
            SecurityContextHolder.setContext(previous);
        }
    }

    private AuthenticatedSession authenticate(
            HttpServletRequest request,
            SessionCredential credential
    ) {
        return isLogout(request)
                ? sessionService.authenticateForLogout(credential)
                : sessionService.authenticate(credential);
    }

    private Optional<IssuedSession> issueLocalSession(HttpServletResponse response) {
        Optional<IssuedSession> issued = localSessionIssuer.issue();
        issued.ifPresent(session -> {
            Duration remaining = Duration.between(clock.instant(), session.absoluteExpiresAt());
            if (remaining.isNegative()) {
                remaining = Duration.ZERO;
            }
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    SessionHttpCookies.session(
                            session.sessionCredential().value(),
                            remaining
                    ).toString()
            );
        });
        return issued;
    }

    private static boolean isLogout(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && WebAuthenticationPaths.LOGOUT.equals(request.getRequestURI());
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            ApplicationException exception
    )
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        Object requestId = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        errorWriter.write(
                response,
                exception,
                requestId == null ? "missing-request-id" : requestId.toString()
        );
    }

    private static void clearSecurityCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, SessionHttpCookies.clearSession().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, SessionHttpCookies.clearCsrf().toString());
    }
}
