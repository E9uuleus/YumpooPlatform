package com.yumpoo.platform.identityaccess.api;

import com.yumpoo.platform.foundation.api.error.ApiErrorWriter;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.application.session.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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

        new SessionAuthenticationFilter(sessionService, mock(PlatformRoleQuery.class), errorWriter)
                .doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(filterChain, never()).doFilter(any(), any());
    }
}
