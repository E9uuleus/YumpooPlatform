package com.yumpoo.platform.identityaccess.application.desktopauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DesktopAuthValueObjectsTest {

    private static final String RFC_7636_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @Test
    void computesTheRfc7636S256Example() {
        assertThat(PkceVerifier.of(RFC_7636_VERIFIER).challenge().value())
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void rejectsMalformedTokensChallengesAndVerifiers() {
        assertThatThrownBy(() -> DesktopAuthToken.of("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PkceS256Challenge("!".repeat(43))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PkceVerifier.of("a".repeat(42))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PkceVerifier.of("a".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secureGeneratorAlwaysProducesA256BitBase64UrlToken() {
        DesktopAuthToken token = new SecureDesktopAuthTokenGenerator().generate();

        assertThat(token.value()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(token.toString()).doesNotContain(token.value());
    }
}
