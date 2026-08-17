package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import com.yumpoo.platform.identityaccess.application.oauth.WeComAuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebAuthenticationConfigurationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T06:00:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void controlledProviderUsesShortLivedOneTimeCodes() {
        ControlledAuthenticationProperties properties = controlled();
        ControlledWebIdentityProvider provider = new ControlledWebIdentityProvider(
                properties,
                CLOCK
        );

        URI callback = provider.buildAuthorizationUri("s".repeat(43));
        String code = query(callback, "code");

        assertThat(callback.getPath()).isEqualTo("/api/v1/auth/wecom/callback");
        assertThat(query(callback, "state")).isEqualTo("s".repeat(43));
        assertThat(provider.exchangeCode(code).corpId()).isEqualTo("corp-test");
        assertThatThrownBy(() -> provider.exchangeCode(code))
                .isInstanceOf(WeComAuthenticationFailedException.class);
    }

    @Test
    void productionAndProviderConflictsFailClosed() {
        WebAuthenticationConfiguration configuration = new WebAuthenticationConfiguration();
        WebOAuthProperties oauth = new WebOAuthProperties();
        ControlledAuthenticationProperties controlled = controlled();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThatThrownBy(() -> configuration.webIdentityProvider(
                oauth,
                controlled,
                production,
                CLOCK
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local/test");

        oauth.setEnabled(true);
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        assertThatThrownBy(() -> configuration.webIdentityProvider(
                oauth,
                controlled,
                test,
                CLOCK
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only one");
    }

    @Test
    void realProviderRequiresExactHttpsCallbackAndIndependentSecret() {
        WebOAuthProperties oauth = enabledOauth();
        oauth.setCallbackUri(URI.create("https://app.example.test/wrong"));
        assertThatThrownBy(oauth::validateForEnabled)
                .isInstanceOf(IllegalStateException.class);

        oauth.setCallbackUri(URI.create("https://app.example.test/api/v1/auth/wecom/callback"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("yumpoo.wecom.directory.directory-secret", "oauth-secret");
        assertThatThrownBy(() -> new WebAuthenticationConfiguration().webIdentityProvider(
                oauth,
                new ControlledAuthenticationProperties(),
                environment,
                CLOCK
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("independent");
    }

    private static ControlledAuthenticationProperties controlled() {
        ControlledAuthenticationProperties properties = new ControlledAuthenticationProperties();
        properties.setEnabled(true);
        properties.setCorpId("corp-test");
        properties.setMemberId("member-test");
        return properties;
    }

    private static WebOAuthProperties enabledOauth() {
        WebOAuthProperties properties = new WebOAuthProperties();
        properties.setEnabled(true);
        properties.setCorpId("corp-test");
        properties.setAgentId("1000002");
        properties.setAppSecret("oauth-secret");
        properties.setCallbackUri(
                URI.create("https://app.example.test/api/v1/auth/wecom/callback")
        );
        properties.setElectronCallbackUri(
                URI.create("https://app.example.test/api/v1/electron/auth/wecom/callback")
        );
        return properties;
    }

    private static String query(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (name.equals(parts[0])) {
                return parts[1];
            }
        }
        throw new IllegalArgumentException("missing query parameter " + name);
    }
}
