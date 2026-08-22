package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.identityaccess.domain.session.LoginSession;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.identityaccess.domain.session.SessionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

    Optional<UserAuthorizationRecord> findUser(UUID userId);

    Optional<UserAuthorizationRecord> lockUser(UUID userId);

    UserAuthorizationRecord incrementAuthorizationVersion(UUID userId);

    void insert(LoginSession session);

    Optional<LoginSession> findByTokenFingerprint(String keyVersion, String fingerprint);

    Optional<LoginSession> lockById(UUID sessionId);

    boolean terminateIfActive(
            UUID sessionId,
            SessionStatus status,
            SessionRevocationReason reason,
            Instant now
    );

    int terminateActiveForUser(
            UUID userId,
            SessionRevocationReason reason,
            Instant now
    );

    boolean convergeCsrf(
            UUID sessionId,
            String expectedKeyVersion,
            String expectedFingerprint,
            CredentialFingerprint replacement
    );

    boolean touchIfActive(
            UUID sessionId,
            long issuedAuthorizationVersion,
            Instant lastSeenAt,
            Instant idleExpiresAt
    );

    int purgeDueSessions(Instant now, int limit);
}
