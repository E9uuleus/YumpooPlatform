package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.session.LoginSession;
import com.yumpoo.platform.identityaccess.domain.session.SessionClientType;
import com.yumpoo.platform.identityaccess.domain.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    private static final UUID SESSION_ID = UUID.fromString(
            "30000000-0000-4000-8000-000000000041"
    );

    private SessionRepository repository;
    private SessionCredentialGenerator credentialGenerator;
    private SessionService service;
    private AuthenticatedSession authenticated;

    @BeforeEach
    void setUp() {
        repository = mock(SessionRepository.class);
        credentialGenerator = mock(SessionCredentialGenerator.class);
        SessionKeyRing keyRing = new SessionKeyRing(
                new SessionKeyRing.Key(
                        "current-v1",
                        HexFormat.of().parseHex("01".repeat(32)),
                        null
                ),
                null
        );
        service = new SessionService(
                repository,
                credentialGenerator,
                keyRing,
                new SessionSettings(
                        Duration.ofMinutes(30),
                        Duration.ofHours(8),
                        Duration.ofDays(1),
                        100,
                        10
                ),
                mock(SessionTerminationService.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        UUID companyId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID userId = UUID.fromString("30000000-0000-4000-8000-000000000003");
        LoginSession session = new LoginSession(
                SESSION_ID,
                companyId,
                userId,
                SessionStatus.ACTIVE,
                "a".repeat(64),
                "current-v1",
                "b".repeat(64),
                "current-v1",
                0,
                SessionClientType.WEB,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                NOW.plusSeconds(1_800),
                NOW.plusSeconds(28_800),
                null,
                null,
                NOW.plusSeconds(28_800 + 86_400)
        );
        authenticated = new AuthenticatedSession(
                session,
                new UserAuthorizationRecord(
                        userId,
                        companyId,
                        EmploymentStatus.ACTIVE,
                        AccountStatus.ENABLED,
                        0,
                        0
                )
        );
    }

    @Test
    void repairCsrfIsDeterministicAndDoesNotUseTheRandomGenerator() {
        when(repository.convergeCsrf(
                eq(SESSION_ID),
                anyString(),
                anyString(),
                any(CredentialFingerprint.class)
        )).thenReturn(true);

        SessionCredential first = service.repairCsrf(authenticated);
        SessionCredential repeated = service.repairCsrf(authenticated);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.value()).matches("[A-Za-z0-9_-]{43}");
        verifyNoInteractions(credentialGenerator);
        ArgumentCaptor<CredentialFingerprint> fingerprints = ArgumentCaptor.forClass(
                CredentialFingerprint.class
        );
        verify(repository, times(2)).convergeCsrf(
                eq(SESSION_ID),
                eq("current-v1"),
                eq("b".repeat(64)),
                fingerprints.capture()
        );
        assertThat(fingerprints.getAllValues()).containsOnly(fingerprints.getValue());
    }

    @Test
    void repairCsrfRejectsAChangedSessionWithoutLeakingCredentials() {
        when(repository.convergeCsrf(
                eq(SESSION_ID),
                anyString(),
                anyString(),
                any(CredentialFingerprint.class)
        )).thenReturn(false);

        assertThatThrownBy(() -> service.repairCsrf(authenticated))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED);
                    assertThat(exception.getMessage())
                            .doesNotContain("a".repeat(64), "b".repeat(64));
                });
        verifyNoInteractions(credentialGenerator);
    }
}
