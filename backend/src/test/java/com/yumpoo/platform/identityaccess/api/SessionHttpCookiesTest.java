package com.yumpoo.platform.identityaccess.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHttpCookiesTest {

    @Test
    void sessionAndCsrfCookiesUseTheHostPrefixContract() {
        String session = SessionHttpCookies.session("session-value", Duration.ofDays(7)).toString();
        String csrf = SessionHttpCookies.csrf("csrf-value", Duration.ofDays(7)).toString();

        assertThat(session)
                .startsWith("__Host-yumpoo-session=session-value;")
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");
        assertThat(csrf)
                .startsWith("__Host-yumpoo-csrf=csrf-value;")
                .contains("Path=/", "Secure", "SameSite=Lax")
                .doesNotContain("HttpOnly", "Domain=");
    }
}
