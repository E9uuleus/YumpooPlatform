package com.yumpoo.platform.identityaccess.infrastructure.wecom;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M012WeComLiveVerificationConfigurationTest {

    private static final String APP_SECRET = "vendor-issued-app-secret-A7f9x2Q4m6P8r1T3";

    private final M012WeComLiveVerificationConfiguration configuration =
            new M012WeComLiveVerificationConfiguration();

    @Test
    void rejectsAnEvidenceKeyReusedFromTheWeComAppSecret() {
        M012WeComProperties properties = validProperties();

        assertThatThrownBy(() -> configuration.m012VerificationReceiptSigner(
                APP_SECRET,
                properties,
                Clock.systemUTC()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("M0-12 evidence HMAC key must be independent from the WeCom app secret");
    }

    @Test
    void acceptsAnIndependentStrongEvidenceKey() {
        assertThatCode(() -> configuration.m012VerificationReceiptSigner(
                "independent-evidence-key-N4b8K2m6Q9v3X7s1",
                validProperties(),
                Clock.systemUTC()
        )).doesNotThrowAnyException();
    }

    private static M012WeComProperties validProperties() {
        M012WeComProperties properties = new M012WeComProperties();
        properties.setEnabled(true);
        properties.setCorpId("ww-config-test");
        properties.setAgentId("100001");
        properties.setAppSecret(APP_SECRET);
        properties.setCallbackUri(URI.create(
                "https://login.example.test/_m0/m0-12/wecom/callback"
        ));
        properties.setAllowedMemberIds(Set.of("member-a"));
        return properties;
    }
}
