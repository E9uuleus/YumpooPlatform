package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
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
import java.util.Optional;

final class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final ApiErrorWriter errorWriter;
    SessionAuthenticationFilter(
            SessionService sessionService,
            ApiErrorWriter errorWriter
    ) {
        this.sessionService = sessionService;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Optional<String> raw;
        try {
            raw = SessionHttpCookies.single(request, SessionHttpCookies.SESSION_COOKIE);
        } catch (IllegalArgumentException exception) {
            reject(request, response, new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED));
            return;
        }
        if (raw.isEmpty()) {
            reject(
                    request,
                    response,
                    new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED)
            );
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SessionCredential credential = new SessionCredential(raw.get());
            AuthenticatedSession authenticated = sessionService.authenticate(credential);
            request.setAttribute(SessionRequestContext.ATTRIBUTE, authenticated);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new SessionAuthenticationToken(new CurrentActor(
                    authenticated.user().userId(),
                    authenticated.user().companyId(),
                    authenticated.user().authorizationVersion()
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
