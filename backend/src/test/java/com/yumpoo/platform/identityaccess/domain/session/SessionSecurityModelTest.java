package com.yumpoo.platform.identityaccess.domain.session;

import com.yumpoo.platform.identityaccess.application.session.CredentialPurpose;
import com.yumpoo.platform.identityaccess.application.session.SecureSessionCredentialGenerator;
import com.yumpoo.platform.identityaccess.application.session.SessionKeyRing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSecurityModelTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-08-13T00:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void opaqueCredentialsUsePurposeSeparatedHmacAndRedactValues() {
        var credential = new SecureSessionCredentialGenerator().generate();
        SessionKeyRing keyRing = new SessionKeyRing(
                new SessionKeyRing.Key("current-v1", new byte[32], null),
                null
        );

        var session = keyRing.fingerprintCurrent(CredentialPurpose.SESSION, credential);
        var csrf = keyRing.fingerprintCurrent(CredentialPurpose.CSRF, credential);

        assertThat(credential.value()).hasSize(43);
        assertThat(session.value()).matches("[0-9a-f]{64}").isNotEqualTo(csrf.value());
        assertThat(credential.toString()).doesNotContain(credential.value());
        assertThat(session.toString()).doesNotContain(session.value());
    }

    @Test
    void previousKeyStopsAtTheExactCutoff() {
        Instant cutoff = ISSUED_AT.plusSeconds(60);
        var credential = new SecureSessionCredentialGenerator().generate();
        SessionKeyRing keyRing = new SessionKeyRing(
                new SessionKeyRing.Key("current", filled(1), null),
                new SessionKeyRing.Key("previous", filled(2), cutoff)
        );

        assertThat(keyRing.fingerprint(
                "previous", CredentialPurpose.SESSION, credential, cutoff.minusNanos(1)
        )).isPresent();
        assertThat(keyRing.fingerprint(
                "previous", CredentialPurpose.SESSION, credential, cutoff
        )).isEmpty();
    }

    @Test
    void csrfRepairCredentialIsStablePerSessionAndCurrentKey() {
        SessionKeyRing keyRing = new SessionKeyRing(
                new SessionKeyRing.Key("current-v1", filled(1), null),
                null
        );
        LoginSession first = session(
                UUID.fromString("30000000-0000-4000-8000-000000000031")
        );
        LoginSession second = session(
                UUID.fromString("30000000-0000-4000-8000-000000000032")
        );

        var firstRepair = keyRing.deriveCurrentCsrfRepairCredential(first);
        var repeatedRepair = keyRing.deriveCurrentCsrfRepairCredential(first);
        var secondRepair = keyRing.deriveCurrentCsrfRepairCredential(second);
        var rotatedKeyRepair = new SessionKeyRing(
                new SessionKeyRing.Key("current-v2", filled(2), null),
                null
        ).deriveCurrentCsrfRepairCredential(first);

        assertThat(firstRepair.value()).matches("[A-Za-z0-9_-]{43}");
        assertThat(repeatedRepair).isEqualTo(firstRepair);
        assertThat(secondRepair).isNotEqualTo(firstRepair);
        assertThat(rotatedKeyRepair).isNotEqualTo(firstRepair);
        assertThat(firstRepair.toString()).doesNotContain(firstRepair.value());
    }

    @Test
    void expirationBoundariesAndTerminalFactsAreExact() {
        LoginSession active = session(
                SessionStatus.ACTIVE,
                null,
                null,
                ISSUED_AT.plusSeconds(60),
                ISSUED_AT.plusSeconds(120)
        );

        assertThat(active.expiredAt(ISSUED_AT.plusSeconds(60).minusNanos(1))).isFalse();
        assertThat(active.expiredAt(ISSUED_AT.plusSeconds(60))).isTrue();
        assertThat(active.expirationReasonAt(ISSUED_AT.plusSeconds(60)))
                .isEqualTo(SessionRevocationReason.IDLE_EXPIRED);
        assertThat(active.expirationReasonAt(ISSUED_AT.plusSeconds(120)))
                .isEqualTo(SessionRevocationReason.ABSOLUTE_EXPIRED);

        assertThatThrownBy(() -> session(
                SessionStatus.REVOKED,
                null,
                null,
                ISSUED_AT.plusSeconds(60),
                ISSUED_AT.plusSeconds(120)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static LoginSession session(
            SessionStatus status,
            Instant revokedAt,
            SessionRevocationReason reason,
            Instant idleExpiry,
            Instant absoluteExpiry
    ) {
        return new LoginSession(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                status,
                FINGERPRINT,
                "current",
                "b".repeat(64),
                "current",
                0,
                SessionClientType.WEB,
                null,
                ISSUED_AT,
                ISSUED_AT,
                idleExpiry,
                absoluteExpiry,
                revokedAt,
                reason,
                absoluteExpiry.plusSeconds(86_400)
        );
    }

    private static LoginSession session(UUID sessionId) {
        return new LoginSession(
                sessionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                SessionStatus.ACTIVE,
                FINGERPRINT,
                "current",
                "b".repeat(64),
                "current",
                0,
                SessionClientType.WEB,
                null,
                ISSUED_AT,
                ISSUED_AT,
                ISSUED_AT.plusSeconds(60),
                ISSUED_AT.plusSeconds(120),
                null,
                null,
                ISSUED_AT.plusSeconds(120 + 86_400)
        );
    }

    private static byte[] filled(int value) {
        return HexFormat.of().parseHex(("0" + value).repeat(32));
    }
}
