package com.yumpoo.platform.identityaccess.domain.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LoginSession(
        UUID id,
        UUID companyId,
        UUID userId,
        SessionStatus status,
        String sessionTokenFingerprint,
        String sessionKeyVersion,
        String csrfTokenFingerprint,
        String csrfKeyVersion,
        long issuedAuthorizationVersion,
        SessionClientType clientType,
        String clientVersion,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        Instant revokedAt,
        SessionRevocationReason revokeReason,
        Instant purgeAfter
) {

    public LoginSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        requireFingerprint(sessionTokenFingerprint, "sessionTokenFingerprint");
        requireKeyVersion(sessionKeyVersion, "sessionKeyVersion");
        if ((csrfTokenFingerprint == null) != (csrfKeyVersion == null)) {
            throw new IllegalArgumentException("CSRF fingerprint facts must be complete");
        }
        if (csrfTokenFingerprint != null) {
            requireFingerprint(csrfTokenFingerprint, "csrfTokenFingerprint");
            requireKeyVersion(csrfKeyVersion, "csrfKeyVersion");
        }
        if (issuedAuthorizationVersion < 0) {
            throw new IllegalArgumentException("issuedAuthorizationVersion must not be negative");
        }
        Objects.requireNonNull(clientType, "clientType must not be null");
        if (clientVersion != null
                && (clientVersion.isBlank()
                || !clientVersion.equals(clientVersion.trim())
                || clientVersion.length() > 64)) {
            throw new IllegalArgumentException("clientVersion is invalid");
        }
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt must not be null");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt must not be null");
        Objects.requireNonNull(purgeAfter, "purgeAfter must not be null");
        if (lastSeenAt.isBefore(issuedAt)
                || !idleExpiresAt.isAfter(lastSeenAt)
                || idleExpiresAt.isAfter(absoluteExpiresAt)
                || !purgeAfter.equals(absoluteExpiresAt.plusSeconds(86_400))) {
            throw new IllegalArgumentException("session lifecycle timestamps are inconsistent");
        }
        boolean activeFacts = revokedAt == null && revokeReason == null;
        boolean terminalFacts = revokedAt != null && revokeReason != null
                && !revokedAt.isBefore(issuedAt);
        if ((status == SessionStatus.ACTIVE && !activeFacts)
                || (status != SessionStatus.ACTIVE && !terminalFacts)) {
            throw new IllegalArgumentException("session status facts are inconsistent");
        }
    }

    public boolean expiredAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return !now.isBefore(idleExpiresAt) || !now.isBefore(absoluteExpiresAt);
    }

    public SessionRevocationReason expirationReasonAt(Instant now) {
        if (!now.isBefore(absoluteExpiresAt)) {
            return SessionRevocationReason.ABSOLUTE_EXPIRED;
        }
        if (!now.isBefore(idleExpiresAt)) {
            return SessionRevocationReason.IDLE_EXPIRED;
        }
        throw new IllegalStateException("session has not expired");
    }

    @Override
    public String toString() {
        return "LoginSession[id=" + id
                + ", companyId=" + companyId
                + ", userId=" + userId
                + ", status=" + status
                + ", credentialFingerprints=REDACTED]";
    }

    private static void requireFingerprint(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static void requireKeyVersion(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
