package com.yumpoo.platform.identityaccess.application.session;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.session.LoginSession;
import com.yumpoo.platform.identityaccess.domain.session.SessionClientType;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.identityaccess.domain.session.SessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository repository;
    private final SessionCredentialGenerator credentialGenerator;
    private final SessionKeyRing keyRing;
    private final SessionSettings settings;
    private final SessionTerminationService terminationService;
    private final Clock clock;

    public SessionService(
            SessionRepository repository,
            SessionCredentialGenerator credentialGenerator,
            SessionKeyRing keyRing,
            SessionSettings settings,
            SessionTerminationService terminationService,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.credentialGenerator = Objects.requireNonNull(
                credentialGenerator,
                "credentialGenerator must not be null"
        );
        this.keyRing = Objects.requireNonNull(keyRing, "keyRing must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.terminationService = Objects.requireNonNull(
                terminationService,
                "terminationService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Transactional
    public IssuedSession issueWebSession(UUID userId, String clientVersion) {
        UserAuthorizationRecord user = repository.lockUser(userId).orElseThrow(
                SessionService::authenticationRequired
        );
        if (!user.activeAndEnabled()) {
            throw authenticationRequired();
        }
        return insertSession(user, SessionClientType.WEB, clientVersion, clock.instant());
    }

    @Transactional
    public IssuedSession rotate(SessionCredential oldCredential) {
        Instant now = clock.instant();
        LoginSession observed = findSession(oldCredential, now).orElseThrow(
                SessionService::authenticationRequired
        );
        UserAuthorizationRecord user = repository.lockUser(observed.userId()).orElseThrow(
                SessionService::authenticationRequired
        );
        LoginSession locked = repository.lockById(observed.id()).orElseThrow(
                SessionService::authenticationRequired
        );
        requireAuthenticatable(locked, user, now);
        if (!repository.terminateIfActive(
                locked.id(),
                SessionStatus.REVOKED,
                SessionRevocationReason.ROTATED,
                now
        )) {
            throw authenticationRequired();
        }
        return insertSession(user, locked.clientType(), locked.clientVersion(), now);
    }

    @Transactional
    public AuthenticatedSession authenticate(SessionCredential credential) {
        Instant now = clock.instant();
        LoginSession session = findSession(credential, now).orElseThrow(
                SessionService::authenticationRequired
        );
        if (!now.isBefore(session.purgeAfter())) {
            throw authenticationRequired();
        }
        UserAuthorizationRecord user = repository.findUser(session.userId()).orElseThrow(
                SessionService::authenticationRequired
        );
        requireAuthenticatable(session, user, now);
        return new AuthenticatedSession(session, user);
    }

    @Transactional
    public AuthenticatedSession authenticateForLogout(SessionCredential credential) {
        Instant now = clock.instant();
        LoginSession session = findSession(credential, now).orElseThrow(
                SessionService::authenticationRequired
        );
        if (!now.isBefore(session.purgeAfter())) {
            throw authenticationRequired();
        }
        UserAuthorizationRecord user = repository.findUser(session.userId()).orElseThrow(
                SessionService::authenticationRequired
        );
        if (session.status() == SessionStatus.REVOKED
                && session.revokeReason() == SessionRevocationReason.USER_LOGOUT) {
            return new AuthenticatedSession(session, user);
        }
        requireAuthenticatable(session, user, now);
        return new AuthenticatedSession(session, user);
    }

    @Transactional
    public boolean logout(AuthenticatedSession authenticatedSession) {
        Objects.requireNonNull(
                authenticatedSession,
                "authenticatedSession must not be null"
        );
        Instant now = clock.instant();
        LoginSession locked = repository.lockById(authenticatedSession.session().id())
                .orElseThrow(SessionService::authenticationRequired);
        if (locked.status() == SessionStatus.REVOKED
                && locked.revokeReason() == SessionRevocationReason.USER_LOGOUT) {
            return false;
        }
        UserAuthorizationRecord user = repository.lockUser(locked.userId()).orElseThrow(
                SessionService::authenticationRequired
        );
        requireAuthenticatable(locked, user, now);
        if (repository.terminateIfActive(
                locked.id(),
                SessionStatus.REVOKED,
                SessionRevocationReason.USER_LOGOUT,
                now
        )) {
            return true;
        }
        LoginSession raced = repository.lockById(locked.id()).orElseThrow(
                SessionService::authenticationRequired
        );
        if (raced.status() == SessionStatus.REVOKED
                && raced.revokeReason() == SessionRevocationReason.USER_LOGOUT) {
            return false;
        }
        throw authenticationRequired();
    }

    @Transactional
    public Optional<SessionCredential> replaceCsrf(
            AuthenticatedSession authenticatedSession
    ) {
        LoginSession session = authenticatedSession.session();
        if (session.csrfKeyVersion() == null || session.csrfTokenFingerprint() == null) {
            return Optional.empty();
        }
        SessionCredential replacement = credentialGenerator.generate();
        CredentialFingerprint fingerprint = keyRing.fingerprintCurrent(
                CredentialPurpose.CSRF,
                replacement
        );
        boolean changed = repository.replaceCsrf(
                session.id(),
                session.csrfKeyVersion(),
                session.csrfTokenFingerprint(),
                fingerprint
        );
        return changed ? Optional.of(replacement) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean verifyCsrf(
            AuthenticatedSession authenticatedSession,
            SessionCredential csrfCredential
    ) {
        LoginSession session = authenticatedSession.session();
        if (session.csrfKeyVersion() == null || session.csrfTokenFingerprint() == null) {
            return false;
        }
        return keyRing.fingerprint(
                        session.csrfKeyVersion(),
                        CredentialPurpose.CSRF,
                        csrfCredential,
                        clock.instant()
                )
                .map(candidate -> SessionKeyRing.matches(
                        candidate.value(),
                        session.csrfTokenFingerprint()
                ))
                .orElse(false);
    }

    @Transactional
    public void touch(AuthenticatedSession authenticatedSession) {
        Instant now = clock.instant();
        LoginSession session = authenticatedSession.session();
        Instant idleExpiresAt = now.plus(settings.idleTimeout());
        if (idleExpiresAt.isAfter(session.absoluteExpiresAt())) {
            idleExpiresAt = session.absoluteExpiresAt();
        }
        if (!idleExpiresAt.isAfter(now)) {
            return;
        }
        repository.touchIfActive(
                session.id(),
                authenticatedSession.user().authorizationVersion(),
                now,
                idleExpiresAt
        );
    }

    @Transactional
    public UserAuthorizationRecord incrementAuthorizationVersion(
            UUID userId,
            SessionRevocationReason reason
    ) {
        if (reason != SessionRevocationReason.EMPLOYMENT_LEFT
                && reason != SessionRevocationReason.ACCOUNT_DISABLED
                && reason != SessionRevocationReason.AUTHORIZATION_CHANGED
                && reason != SessionRevocationReason.ADMIN_FORCED) {
            throw new IllegalArgumentException("reason cannot change authorization version");
        }
        UserAuthorizationRecord user = repository.incrementAuthorizationVersion(userId);
        repository.terminateActiveForUser(userId, reason, clock.instant());
        return user;
    }

    @Transactional
    public int purgeDueSessions() {
        int total = 0;
        Instant now = clock.instant();
        for (int batch = 0; batch < settings.purgeMaxBatches(); batch++) {
            int deleted = repository.purgeDueSessions(now, settings.purgeBatchSize());
            total += deleted;
            if (deleted < settings.purgeBatchSize()) {
                break;
            }
        }
        return total;
    }

    private IssuedSession insertSession(
            UserAuthorizationRecord user,
            SessionClientType clientType,
            String clientVersion,
            Instant now
    ) {
        SessionCredential sessionCredential = credentialGenerator.generate();
        SessionCredential csrfCredential = credentialGenerator.generate();
        CredentialFingerprint sessionFingerprint = keyRing.fingerprintCurrent(
                CredentialPurpose.SESSION,
                sessionCredential
        );
        CredentialFingerprint csrfFingerprint = keyRing.fingerprintCurrent(
                CredentialPurpose.CSRF,
                csrfCredential
        );
        Instant absoluteExpiresAt = now.plus(settings.absoluteTimeout());
        Instant idleExpiresAt = now.plus(settings.idleTimeout());
        if (idleExpiresAt.isAfter(absoluteExpiresAt)) {
            idleExpiresAt = absoluteExpiresAt;
        }
        LoginSession session = new LoginSession(
                UUID.randomUUID(),
                user.companyId(),
                user.userId(),
                SessionStatus.ACTIVE,
                sessionFingerprint.value(),
                sessionFingerprint.keyVersion(),
                csrfFingerprint.value(),
                csrfFingerprint.keyVersion(),
                user.authorizationVersion(),
                clientType,
                clientVersion,
                now,
                now,
                idleExpiresAt,
                absoluteExpiresAt,
                null,
                null,
                absoluteExpiresAt.plus(settings.revokedRetention())
        );
        repository.insert(session);
        return new IssuedSession(session, sessionCredential, csrfCredential);
    }

    private Optional<LoginSession> findSession(SessionCredential credential, Instant now) {
        for (CredentialFingerprint candidate : keyRing.candidates(
                CredentialPurpose.SESSION,
                credential,
                now
        )) {
            Optional<LoginSession> session = repository.findByTokenFingerprint(
                    candidate.keyVersion(),
                    candidate.value()
            );
            if (session.isPresent()) {
                return session;
            }
        }
        return Optional.empty();
    }

    private void requireAuthenticatable(
            LoginSession session,
            UserAuthorizationRecord user,
            Instant now
    ) {
        if (session.status() != SessionStatus.ACTIVE) {
            if (session.revokeReason() == SessionRevocationReason.EMPLOYMENT_LEFT
                    || session.revokeReason() == SessionRevocationReason.ACCOUNT_DISABLED) {
                throw new ApplicationException(StandardErrorCode.ACCOUNT_DISABLED);
            }
            throw authenticationRequired();
        }
        if (session.expiredAt(now)) {
            terminationService.terminateAuthenticationFailure(
                    session.id(),
                    SessionStatus.EXPIRED,
                    session.expirationReasonAt(now),
                    now
            );
            throw authenticationRequired();
        }
        if (!user.activeAndEnabled()) {
            SessionRevocationReason reason = user.accountStatus() == AccountStatus.DISABLED
                    ? SessionRevocationReason.ACCOUNT_DISABLED
                    : SessionRevocationReason.EMPLOYMENT_LEFT;
            terminationService.terminateAuthenticationFailure(
                    session.id(),
                    SessionStatus.REVOKED,
                    reason,
                    now
            );
            throw new ApplicationException(StandardErrorCode.ACCOUNT_DISABLED);
        }
        if (session.issuedAuthorizationVersion() != user.authorizationVersion()) {
            terminationService.terminateAuthenticationFailure(
                    session.id(),
                    SessionStatus.REVOKED,
                    SessionRevocationReason.AUTHORIZATION_CHANGED,
                    now
            );
            throw authenticationRequired();
        }
    }

    private static ApplicationException authenticationRequired() {
        return new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
