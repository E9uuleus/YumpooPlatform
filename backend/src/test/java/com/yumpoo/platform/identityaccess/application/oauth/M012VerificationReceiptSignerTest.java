package com.yumpoo.platform.identityaccess.application.oauth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M012VerificationReceiptSignerTest {

    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final String KEY = "m0-12-evidence-test-key-32-bytes-minimum";

    @Test
    void signsStableDomainSeparatedFingerprintsWithoutExposingIdentity() {
        M012VerificationReceiptSigner signer = signer();

        M012VerificationReceipt first = signer.sign("corp-secret-id", "member-secret-id", "request-1");
        M012VerificationReceipt second = signer.sign("corp-secret-id", "member-secret-id", "request-2");

        assertThat(first.corpFingerprint()).isEqualTo(second.corpFingerprint());
        assertThat(first.memberFingerprint()).isEqualTo(second.memberFingerprint());
        assertThat(first.corpFingerprint()).isNotEqualTo(first.memberFingerprint());
        assertThat(first.toString())
                .doesNotContain("corp-secret-id")
                .doesNotContain("member-secret-id")
                .doesNotContain(KEY);
        assertThat(signer.verifies(first)).isTrue();
    }

    @Test
    void rejectsTamperingAndWeakOrPlaceholderKeys() {
        M012VerificationReceiptSigner signer = signer();
        M012VerificationReceipt signed = signer.sign("corp", "member", "request-1");
        M012VerificationReceipt tampered = new M012VerificationReceipt(
                signed.schemaVersion(),
                signed.status(),
                "request-2",
                signed.corpFingerprint(),
                signed.memberFingerprint(),
                signed.verifiedAt(),
                signed.signature()
        );

        assertThat(signer.verifies(tampered)).isFalse();
        assertThatThrownBy(() -> new M012VerificationReceiptSigner("too-short", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new M012VerificationReceiptSigner("x".repeat(32), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new M012VerificationReceiptSigner(
                "change-me-before-running-live-verification",
                Clock.systemUTC()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static M012VerificationReceiptSigner signer() {
        return new M012VerificationReceiptSigner(KEY, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
