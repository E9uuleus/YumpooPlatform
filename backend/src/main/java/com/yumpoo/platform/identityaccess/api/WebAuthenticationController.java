package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.api.web.ApiV1Controller;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.identityaccess.application.authentication.CurrentAuthenticationQueryService;
import com.yumpoo.platform.identityaccess.application.authentication.WebAuthenticationService;
import com.yumpoo.platform.identityaccess.application.authentication.WebLoginAuthorization;
import com.yumpoo.platform.identityaccess.application.authentication.WebLogoutService;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

@ApiV1Controller
public final class WebAuthenticationController {

    public static final String OAUTH_NONCE_COOKIE = "__Host-yumpoo-oauth-nonce";
    private static final String NO_STORE = "no-store, no-cache, must-revalidate";

    private final WebAuthenticationService authenticationService;
    private final WebLogoutService logoutService;
    private final CurrentAuthenticationQueryService currentQuery;
    private final CurrentActorProvider currentActorProvider;
    private final ApiErrorWriter errorWriter;
    private final Clock clock;

    public WebAuthenticationController(
            WebAuthenticationService authenticationService,
            WebLogoutService logoutService,
            CurrentAuthenticationQueryService currentQuery,
            CurrentActorProvider currentActorProvider,
            ApiErrorWriter errorWriter,
            Clock clock
    ) {
        this.authenticationService = authenticationService;
        this.logoutService = logoutService;
        this.currentQuery = currentQuery;
        this.currentActorProvider = currentActorProvider;
        this.errorWriter = errorWriter;
        this.clock = clock;
    }

    @GetMapping("/auth/wecom/authorize")
    void authorize(HttpServletRequest request, HttpServletResponse response) throws IOException {
        applySecurityHeaders(response);
        String requestId = requestId(request);
        try {
            WebLoginAuthorization authorization = authenticationService.begin(requestId);
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(
                    HttpHeaders.LOCATION,
                    authorization.authorizationUri().toASCIIString()
            );
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    nonceCookie(authorization.nonce().value()).toString()
            );
        } catch (ApplicationException exception) {
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(
                    HttpHeaders.LOCATION,
                    exception.errorCode() == com.yumpoo.platform.foundation.application.error.StandardErrorCode.DEPENDENCY_UNAVAILABLE
                            ? "/login?reason=unavailable"
                            : "/login?reason=authentication"
            );
        }
    }

    @GetMapping("/auth/wecom/callback")
    void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = OAUTH_NONCE_COOKIE, required = false) String nonce,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        applySecurityHeaders(response);
        response.addHeader(HttpHeaders.SET_COOKIE, clearNonceCookie().toString());
        String requestId = requestId(request);
        try {
            IssuedSession issued = authenticationService.complete(code, state, nonce);
            Duration remaining = Duration.between(clock.instant(), issued.absoluteExpiresAt());
            if (remaining.isNegative()) {
                remaining = Duration.ZERO;
            }
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    SessionHttpCookies.session(
                            issued.sessionCredential().value(),
                            remaining
                    ).toString()
            );
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    SessionHttpCookies.csrf(
                            issued.csrfCredential().value(),
                            remaining
                    ).toString()
            );
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, "/");
        } catch (ApplicationException exception) {
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(
                    HttpHeaders.LOCATION,
                    exception.errorCode() == com.yumpoo.platform.foundation.application.error.StandardErrorCode.DEPENDENCY_UNAVAILABLE
                            ? "/login?reason=unavailable"
                            : "/login?reason=authentication"
            );
        }
    }

    @PostMapping("/auth/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        logoutService.logout(SessionRequestContext.required(request));
        response.addHeader(HttpHeaders.SET_COOKIE, SessionHttpCookies.clearSession().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, SessionHttpCookies.clearCsrf().toString());
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    @GetMapping("/auth/me")
    CurrentAuthenticationResponse me(HttpServletRequest request) {
        CurrentActor actor = currentActorProvider.requiredActive();
        return CurrentAuthenticationResponse.from(
                currentQuery.current(SessionRequestContext.required(request)),
                actor.platformRoles()
        );
    }

    private static void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"
        );
    }

    private static ResponseCookie nonceCookie(String value) {
        return ResponseCookie.from(OAUTH_NONCE_COOKIE, value)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(WebAuthenticationService.ATTEMPT_TTL)
                .build();
    }

    private static ResponseCookie clearNonceCookie() {
        return ResponseCookie.from(OAUTH_NONCE_COOKIE, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdContext.ATTRIBUTE_NAME);
        if (value instanceof String requestId && RequestIdContext.isValid(requestId)) {
            return requestId;
        }
        throw new IllegalStateException("requestId filter did not initialize the request");
    }
}
