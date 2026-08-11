package com.yumpoo.platform.identityaccess.application.desktopauth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopAuthValueObjectsTest {

    private static final String RFC_7636_VERIFIER =
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @Test
    void computesTheRfc7636S256Example() {
        assertThat(PkceVerifier.of(RFC_7636_VERIFIER).challenge().value())
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void rejectsMalformedTokensChallengesAndVerifiers() {
        assertThatThrownBy(() -> DesktopAuthToken.of("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PkceS256Challenge("!".repeat(43)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PkceVerifier.of("a".repeat(42)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PkceVerifier.of("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secureGeneratorAlwaysProducesA256BitBase64UrlToken() {
        DesktopAuthToken token = new SecureDesktopAuthTokenGenerator().generate();

        assertThat(token.value()).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(token.toString()).doesNotContain(token.value());
    }

    @Test
    void receiptContainsOnlyFingerprintsAndHasAVerifiableSignature() {
        M015VerificationReceiptSigner signer = new M015VerificationReceiptSigner(
                "m015-test-key-0123456789-abcdef-!@#",
                Clock.fixed(Instant.parse("2026-08-11T01:00:00Z"), ZoneOffset.UTC)
        );
        DesktopIdentityFingerprint fingerprint = signer.fingerprint("raw-corp", "raw-member");

        M015VerificationReceipt receipt = signer.sign(fingerprint, "m015.request-1");

        assertThat(receipt.corpFingerprint()).hasSize(64).doesNotContain("raw-corp");
        assertThat(receipt.memberFingerprint()).hasSize(64).doesNotContain("raw-member");
        assertThat(receipt.verifiedAt()).isEqualTo("2026-08-11T01:00:00Z");
        assertThat(signer.verifies(receipt)).isTrue();
    }
}
