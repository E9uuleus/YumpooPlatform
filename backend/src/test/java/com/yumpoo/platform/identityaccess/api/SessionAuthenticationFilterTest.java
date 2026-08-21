package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import com.yumpoo.platform.identityaccess.application.session.AuthenticatedSession;
import com.yumpoo.platform.identityaccess.application.session.IssuedSession;
import com.yumpoo.platform.identityaccess.application.session.SessionCredential;
import com.yumpoo.platform.identityaccess.application.session.UserAuthorizationRecord;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.session.LoginSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionAuthenticationFilterTest {

    @Test
    void missingCookieIssuesARealLocalSessionAndContinuesTheSecurityChain() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        PlatformRoleQuery roleQuery = mock(PlatformRoleQuery.class);
        ApiErrorWriter errorWriter = mock(ApiErrorWriter.class);
        LocalSessionIssuer localSessionIssuer = mock(LocalSessionIssuer.class);
        FilterChain filterChain = mock(FilterChain.class);
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        SessionCredential sessionCredential = new SessionCredential("s".repeat(43));
        SessionCredential csrfCredential = new SessionCredential("c".repeat(43));
        LoginSession loginSession = mock(LoginSession.class);
        Instant expiresAt = Instant.parse("2026-08-22T00:00:00Z");
        when(loginSession.absoluteExpiresAt()).thenReturn(expiresAt);
        IssuedSession issued = new IssuedSession(
                loginSession,
                sessionCredential,
                csrfCredential
        );
        UserAuthorizationRecord user = new UserAuthorizationRecord(
                userId,
                companyId,
                EmploymentStatus.ACTIVE,
                AccountStatus.ENABLED,
                2,
                3
        );
        when(localSessionIssuer.issue()).thenReturn(Optional.of(issued));
        when(sessionService.authenticate(sessionCredential)).thenReturn(
                new AuthenticatedSession(loginSession, user)
        );
        when(roleQuery.findActiveRoleCodes(companyId, userId)).thenReturn(
                Set.of(PlatformRoleCode.COMPANY_ADMIN, PlatformRoleCode.APP_MANAGER)
        );

        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/auth/me"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-21T00:00:00Z"),
                java.time.ZoneOffset.UTC
        );

        new SessionAuthenticationFilter(
                sessionService,
                roleQuery,
                errorWriter,
                localSessionIssuer,
                clock
        ).doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getHeaders("Set-Cookie"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith(SessionHttpCookies.SESSION_COOKIE + "=")
                        .contains("Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(response.getHeaders("Set-Cookie"))
                .noneSatisfy(value -> assertThat(value)
                        .startsWith(SessionHttpCookies.CSRF_COOKIE + "="));
        verify(errorWriter, never()).write(any(), any(ApplicationException.class), anyString());
    }

    @Test
    void databaseFailureReturnsDependencyUnavailableWithoutCallingTheEndpoint() throws Exception {
        SessionService sessionService = mock(SessionService.class);
        ApiErrorWriter errorWriter = mock(ApiErrorWriter.class);
        FilterChain filterChain = mock(FilterChain.class);
        when(sessionService.authenticate(any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        doAnswer(invocation -> {
            ApplicationException exception = invocation.getArgument(1);
            assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.DEPENDENCY_UNAVAILABLE);
            ((MockHttpServletResponse) invocation.getArgument(0)).setStatus(503);
            return null;
        }).when(errorWriter).write(any(), any(ApplicationException.class), anyString());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/items");
        request.setCookies(new Cookie(
                SessionHttpCookies.SESSION_COOKIE,
                "a".repeat(43)
        ));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SessionAuthenticationFilter(
                sessionService,
                mock(PlatformRoleQuery.class),
                errorWriter,
                mock(LocalSessionIssuer.class),
                Clock.systemUTC()
        )
                .doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(filterChain, never()).doFilter(any(), any());
    }
}
