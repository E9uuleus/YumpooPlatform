package com.yumpoo.platform.identityaccess.infrastructure.session;

import com.yumpoo.platform.identityaccess.application.session.CredentialFingerprint;
import com.yumpoo.platform.identityaccess.application.session.SessionRepository;
import com.yumpoo.platform.identityaccess.application.session.UserAuthorizationRecord;
import com.yumpoo.platform.identityaccess.domain.identity.AccountStatus;
import com.yumpoo.platform.identityaccess.domain.identity.EmploymentStatus;
import com.yumpoo.platform.identityaccess.domain.session.LoginSession;
import com.yumpoo.platform.identityaccess.domain.session.SessionClientType;
import com.yumpoo.platform.identityaccess.domain.session.SessionRevocationReason;
import com.yumpoo.platform.identityaccess.domain.session.SessionStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcSessionRepository implements SessionRepository {

    private static final String USER_COLUMNS = """
            id, company_id, employment_status, account_status,
            authorization_version, row_version
            """;
    private static final String SESSION_COLUMNS = """
            id, company_id, user_id, status,
            session_token_fingerprint, session_key_version,
            csrf_token_fingerprint, csrf_key_version,
            issued_authorization_version, client_type, client_version,
            issued_at, last_seen_at, idle_expires_at, absolute_expires_at,
            revoked_at, revoke_reason, purge_after
            """;

    private final JdbcClient jdbcClient;

    public JdbcSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<UserAuthorizationRecord> findUser(UUID userId) {
        return singleUser("""
                SELECT %s
                FROM yumpoo.identity_user
                WHERE id = :userId
                """.formatted(USER_COLUMNS), userId);
    }

    @Override
    public Optional<UserAuthorizationRecord> lockUser(UUID userId) {
        return singleUser("""
                SELECT %s
                FROM yumpoo.identity_user
                WHERE id = :userId
                FOR UPDATE
                """.formatted(USER_COLUMNS), userId);
    }

    @Override
    public UserAuthorizationRecord incrementAuthorizationVersion(UUID userId) {
        List<UserAuthorizationRecord> users = jdbcClient.sql("""
                        UPDATE yumpoo.identity_user
                        SET authorization_version = authorization_version + 1,
                            row_version = row_version + 1,
                            updated_at = transaction_timestamp()
                        WHERE id = :userId
                        RETURNING %s
                        """.formatted(USER_COLUMNS))
                .param("userId", userId)
                .query(JdbcSessionRepository::mapUser)
                .list();
        if (users.size() != 1) {
            throw new IllegalStateException("authorization version update must affect one user");
        }
        return users.getFirst();
    }

    @Override
    public void insert(LoginSession session) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO yumpoo.login_session (
                            id, company_id, user_id, status,
                            session_token_fingerprint, session_key_version,
                            csrf_token_fingerprint, csrf_key_version,
                            issued_authorization_version, client_type, client_version,
                            issued_at, last_seen_at, idle_expires_at, absolute_expires_at,
                            revoked_at, revoke_reason, purge_after
                        ) VALUES (
                            :id, :companyId, :userId, :status,
                            :sessionFingerprint, :sessionKeyVersion,
                            :csrfFingerprint, :csrfKeyVersion,
                            :authorizationVersion, :clientType, :clientVersion,
                            :issuedAt, :lastSeenAt, :idleExpiresAt, :absoluteExpiresAt,
                            :revokedAt, :revokeReason, :purgeAfter
                        )
                        """)
                .param("id", session.id())
                .param("companyId", session.companyId())
                .param("userId", session.userId())
                .param("status", session.status().name())
                .param("sessionFingerprint", session.sessionTokenFingerprint())
                .param("sessionKeyVersion", session.sessionKeyVersion())
                .param("csrfFingerprint", session.csrfTokenFingerprint())
                .param("csrfKeyVersion", session.csrfKeyVersion())
                .param("authorizationVersion", session.issuedAuthorizationVersion())
                .param("clientType", session.clientType().name())
                .param("clientVersion", session.clientVersion())
                .param("issuedAt", utc(session.issuedAt()))
                .param("lastSeenAt", utc(session.lastSeenAt()))
                .param("idleExpiresAt", utc(session.idleExpiresAt()))
                .param("absoluteExpiresAt", utc(session.absoluteExpiresAt()))
                .param("revokedAt", nullableUtc(session.revokedAt()))
                .param("revokeReason", session.revokeReason() == null
                        ? null : session.revokeReason().name())
                .param("purgeAfter", utc(session.purgeAfter()))
                .update();
        requireOne(inserted, "insert LoginSession");
    }

    @Override
    public Optional<LoginSession> findByTokenFingerprint(
            String keyVersion,
            String fingerprint
    ) {
        return singleSession(jdbcClient.sql("""
                        SELECT %s
                        FROM yumpoo.login_session
                        WHERE session_key_version = :keyVersion
                          AND session_token_fingerprint = :fingerprint
                        """.formatted(SESSION_COLUMNS))
                .param("keyVersion", keyVersion)
                .param("fingerprint", fingerprint)
                .query(JdbcSessionRepository::mapSession)
                .list());
    }

    @Override
    public Optional<LoginSession> lockById(UUID sessionId) {
        return singleSession(jdbcClient.sql("""
                        SELECT %s
                        FROM yumpoo.login_session
                        WHERE id = :sessionId
                        FOR UPDATE
                        """.formatted(SESSION_COLUMNS))
                .param("sessionId", sessionId)
                .query(JdbcSessionRepository::mapSession)
                .list());
    }

    @Override
    public boolean terminateIfActive(
            UUID sessionId,
            SessionStatus status,
            SessionRevocationReason reason,
            Instant now
    ) {
        if (status == SessionStatus.ACTIVE) {
            throw new IllegalArgumentException("terminal status is required");
        }
        return jdbcClient.sql("""
                        UPDATE yumpoo.login_session
                        SET status = :status,
                            revoked_at = :now,
                            revoke_reason = :reason
                        WHERE id = :sessionId
                          AND status = 'ACTIVE'
                        """)
                .param("status", status.name())
                .param("now", utc(now))
                .param("reason", reason.name())
                .param("sessionId", sessionId)
                .update() == 1;
    }

    @Override
    public int terminateActiveForUser(
            UUID userId,
            SessionRevocationReason reason,
            Instant now
    ) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.login_session
                        SET status = 'REVOKED',
                            revoked_at = :now,
                            revoke_reason = :reason
                        WHERE user_id = :userId
                          AND status = 'ACTIVE'
                          AND idle_expires_at > :now
                          AND absolute_expires_at > :now
                        """)
                .param("now", utc(now))
                .param("reason", reason.name())
                .param("userId", userId)
                .update();
    }

    @Override
    public boolean replaceCsrf(
            UUID sessionId,
            String expectedKeyVersion,
            String expectedFingerprint,
            CredentialFingerprint replacement
    ) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.login_session
                        SET csrf_key_version = :replacementKeyVersion,
                            csrf_token_fingerprint = :replacementFingerprint
                        WHERE id = :sessionId
                          AND status = 'ACTIVE'
                          AND csrf_key_version = :expectedKeyVersion
                          AND csrf_token_fingerprint = :expectedFingerprint
                        """)
                .param("replacementKeyVersion", replacement.keyVersion())
                .param("replacementFingerprint", replacement.value())
                .param("sessionId", sessionId)
                .param("expectedKeyVersion", expectedKeyVersion)
                .param("expectedFingerprint", expectedFingerprint)
                .update() == 1;
    }

    @Override
    public boolean touchIfActive(
            UUID sessionId,
            long issuedAuthorizationVersion,
            Instant lastSeenAt,
            Instant idleExpiresAt
    ) {
        return jdbcClient.sql("""
                        UPDATE yumpoo.login_session
                        SET last_seen_at = :lastSeenAt,
                            idle_expires_at = :idleExpiresAt
                        WHERE id = :sessionId
                          AND status = 'ACTIVE'
                          AND issued_authorization_version = :authorizationVersion
                          AND absolute_expires_at > :lastSeenAt
                        """)
                .param("lastSeenAt", utc(lastSeenAt))
                .param("idleExpiresAt", utc(idleExpiresAt))
                .param("sessionId", sessionId)
                .param("authorizationVersion", issuedAuthorizationVersion)
                .update() == 1;
    }

    @Override
    public int purgeDueSessions(Instant now, int limit) {
        return jdbcClient.sql("""
                        WITH purgeable AS (
                            SELECT id
                            FROM yumpoo.login_session
                            WHERE purge_after <= :now
                            ORDER BY purge_after, id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM yumpoo.login_session session
                        USING purgeable
                        WHERE session.id = purgeable.id
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .update();
    }

    private Optional<UserAuthorizationRecord> singleUser(String sql, UUID userId) {
        List<UserAuthorizationRecord> users = jdbcClient.sql(sql)
                .param("userId", userId)
                .query(JdbcSessionRepository::mapUser)
                .list();
        if (users.size() > 1) {
            throw new IllegalStateException("User id uniqueness was violated");
        }
        return users.stream().findFirst();
    }

    private static Optional<LoginSession> singleSession(List<LoginSession> sessions) {
        if (sessions.size() > 1) {
            throw new IllegalStateException("Session uniqueness was violated");
        }
        return sessions.stream().findFirst();
    }

    private static UserAuthorizationRecord mapUser(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new UserAuthorizationRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                EmploymentStatus.valueOf(resultSet.getString("employment_status")),
                AccountStatus.valueOf(resultSet.getString("account_status")),
                resultSet.getLong("authorization_version"),
                resultSet.getLong("row_version")
        );
    }

    private static LoginSession mapSession(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String revokeReason = resultSet.getString("revoke_reason");
        return new LoginSession(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("company_id", UUID.class),
                resultSet.getObject("user_id", UUID.class),
                SessionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("session_token_fingerprint"),
                resultSet.getString("session_key_version"),
                resultSet.getString("csrf_token_fingerprint"),
                resultSet.getString("csrf_key_version"),
                resultSet.getLong("issued_authorization_version"),
                SessionClientType.valueOf(resultSet.getString("client_type")),
                resultSet.getString("client_version"),
                instant(resultSet, "issued_at"),
                instant(resultSet, "last_seen_at"),
                instant(resultSet, "idle_expires_at"),
                instant(resultSet, "absolute_expires_at"),
                nullableInstant(resultSet, "revoked_at"),
                revokeReason == null ? null : SessionRevocationReason.valueOf(revokeReason),
                instant(resultSet, "purge_after")
        );
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableUtc(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static void requireOne(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(operation + " did not affect exactly one row");
        }
    }
}
