package com.yumpoo.platform.identityaccess.infrastructure.desktopauth;

import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthAttempt;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthAttemptStore;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthExchange;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopAuthTokenHash;
import com.yumpoo.platform.identityaccess.application.desktopauth.DesktopIdentityFingerprint;
import com.yumpoo.platform.identityaccess.application.desktopauth.PkceS256Challenge;
import com.yumpoo.platform.identityaccess.application.desktopauth.ProductDesktopAuthAttempt;
import com.yumpoo.platform.identityaccess.application.desktopauth.ProductDesktopAuthExchange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDesktopAuthAttemptRepository implements DesktopAuthAttemptStore {

    private static final String INSERT_ATTEMPT = """
            INSERT INTO yumpoo.desktop_auth_attempt (
                desktop_state_hash,
                oauth_state_hash,
                pkce_s256_challenge,
                request_id,
                created_at,
                authorize_expires_at
            ) VALUES (
                :desktopStateHash,
                :oauthStateHash,
                :pkceChallenge,
                :requestId,
                :createdAt,
                :authorizeExpiresAt
            )
            """;

    private static final String ISSUE_HANDOFF = """
            UPDATE yumpoo.desktop_auth_attempt
            SET handoff_code_hash = :handoffCodeHash,
                corp_fingerprint = :corpFingerprint,
                member_fingerprint = :memberFingerprint,
                handoff_issued_at = :issuedAt,
                handoff_expires_at = :expiresAt
            WHERE oauth_state_hash = :oauthStateHash
              AND desktop_state_hash = :desktopStateHash
              AND handoff_code_hash IS NULL
              AND consumed_at IS NULL
              AND created_at <= :issuedAt
              AND authorize_expires_at > :issuedAt
            """;

    /** The one statement below is the replay and concurrent-exchange boundary. */
    private static final String CONSUME_HANDOFF = """
            UPDATE yumpoo.desktop_auth_attempt
            SET consumed_at = :consumedAt
            WHERE desktop_state_hash = :desktopStateHash
              AND handoff_code_hash = :handoffCodeHash
              AND pkce_s256_challenge = :pkceChallenge
              AND handoff_issued_at IS NOT NULL
              AND handoff_issued_at <= :consumedAt
              AND handoff_expires_at > :consumedAt
              AND consumed_at IS NULL
            RETURNING corp_fingerprint,
                      member_fingerprint,
                      handoff_issued_at,
                      consumed_at
            """;

    private static final String INSERT_PRODUCT_ATTEMPT = """
            INSERT INTO yumpoo.desktop_auth_attempt (
                desktop_state_hash,
                oauth_state_hash,
                pkce_s256_challenge,
                request_id,
                client_version,
                client_protocol_version,
                created_at,
                authorize_expires_at
            ) VALUES (
                :stateHash,
                :stateHash,
                :pkceChallenge,
                :requestId,
                :clientVersion,
                :clientProtocolVersion,
                :createdAt,
                :authorizeExpiresAt
            )
            """;

    private static final String CLAIM_PRODUCT_AUTHORIZATION = """
            UPDATE yumpoo.desktop_auth_attempt
            SET authorization_claimed_at = :claimedAt
            WHERE desktop_state_hash = :stateHash
              AND oauth_state_hash = :stateHash
              AND client_version IS NOT NULL
              AND authorization_claimed_at IS NULL
              AND handoff_code_hash IS NULL
              AND consumed_at IS NULL
              AND created_at <= :claimedAt
              AND authorize_expires_at > :claimedAt
            """;

    private static final String ISSUE_PRODUCT_HANDOFF = """
            UPDATE yumpoo.desktop_auth_attempt
            SET handoff_code_hash = :handoffCodeHash,
                authenticated_user_id = :userId,
                handoff_issued_at = :issuedAt,
                handoff_expires_at = :expiresAt
            WHERE desktop_state_hash = :stateHash
              AND oauth_state_hash = :stateHash
              AND client_version IS NOT NULL
              AND authorization_claimed_at IS NOT NULL
              AND handoff_code_hash IS NULL
              AND consumed_at IS NULL
              AND authorize_expires_at > :issuedAt
            """;

    private static final String CONSUME_PRODUCT_HANDOFF = """
            UPDATE yumpoo.desktop_auth_attempt
            SET consumed_at = :consumedAt
            WHERE desktop_state_hash = :stateHash
              AND oauth_state_hash = :stateHash
              AND handoff_code_hash = :handoffCodeHash
              AND pkce_s256_challenge = :pkceChallenge
              AND authenticated_user_id IS NOT NULL
              AND client_version IS NOT NULL
              AND handoff_issued_at IS NOT NULL
              AND handoff_issued_at <= :consumedAt
              AND handoff_expires_at > :consumedAt
              AND consumed_at IS NULL
            RETURNING authenticated_user_id,
                      client_version,
                      client_protocol_version,
                      handoff_issued_at,
                      consumed_at
            """;

    private final JdbcClient jdbcClient;

    public JdbcDesktopAuthAttemptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    @Transactional
    public void create(DesktopAuthAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        int rows = jdbcClient.sql(INSERT_ATTEMPT)
                .param("desktopStateHash", attempt.desktopStateHash().value())
                .param("oauthStateHash", attempt.oauthStateHash().value())
                .param("pkceChallenge", attempt.pkceChallenge().value())
                .param("requestId", attempt.requestId())
                .param("createdAt", utc(attempt.createdAt()))
                .param("authorizeExpiresAt", utc(attempt.authorizeExpiresAt()))
                .update();
        if (rows != 1) {
            throw new IllegalStateException("desktop auth attempt was not created");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean issueHandoff(
            DesktopAuthTokenHash oauthStateHash,
            DesktopAuthTokenHash desktopStateHash,
            DesktopAuthTokenHash handoffCodeHash,
            DesktopIdentityFingerprint identityFingerprint,
            Instant issuedAt,
            Instant expiresAt
    ) {
        Objects.requireNonNull(oauthStateHash, "oauthStateHash must not be null");
        Objects.requireNonNull(desktopStateHash, "desktopStateHash must not be null");
        Objects.requireNonNull(handoffCodeHash, "handoffCodeHash must not be null");
        Objects.requireNonNull(identityFingerprint, "identityFingerprint must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        return jdbcClient.sql(ISSUE_HANDOFF)
                .param("oauthStateHash", oauthStateHash.value())
                .param("desktopStateHash", desktopStateHash.value())
                .param("handoffCodeHash", handoffCodeHash.value())
                .param("corpFingerprint", identityFingerprint.corpFingerprint())
                .param("memberFingerprint", identityFingerprint.memberFingerprint())
                .param("issuedAt", utc(issuedAt))
                .param("expiresAt", utc(expiresAt))
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DesktopAuthExchange> consume(
            DesktopAuthTokenHash desktopStateHash,
            DesktopAuthTokenHash handoffCodeHash,
            PkceS256Challenge pkceChallenge,
            Instant consumedAt
    ) {
        Objects.requireNonNull(desktopStateHash, "desktopStateHash must not be null");
        Objects.requireNonNull(handoffCodeHash, "handoffCodeHash must not be null");
        Objects.requireNonNull(pkceChallenge, "pkceChallenge must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        return jdbcClient.sql(CONSUME_HANDOFF)
                .param("desktopStateHash", desktopStateHash.value())
                .param("handoffCodeHash", handoffCodeHash.value())
                .param("pkceChallenge", pkceChallenge.value())
                .param("consumedAt", utc(consumedAt))
                .query((resultSet, rowNumber) -> new DesktopAuthExchange(
                        new DesktopIdentityFingerprint(
                                resultSet.getString("corp_fingerprint"),
                                resultSet.getString("member_fingerprint")
                        ),
                        resultSet.getObject("handoff_issued_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("consumed_at", OffsetDateTime.class).toInstant()
                ))
                .optional();
    }

    @Override
    @Transactional
    public void createProduct(ProductDesktopAuthAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        int rows = jdbcClient.sql(INSERT_PRODUCT_ATTEMPT)
                .param("stateHash", attempt.stateHash().value())
                .param("pkceChallenge", attempt.pkceChallenge().value())
                .param("requestId", attempt.requestId())
                .param("clientVersion", attempt.clientVersion())
                .param("clientProtocolVersion", attempt.clientProtocolVersion())
                .param("createdAt", utc(attempt.createdAt()))
                .param("authorizeExpiresAt", utc(attempt.authorizeExpiresAt()))
                .update();
        if (rows != 1) {
            throw new IllegalStateException("product desktop auth attempt was not created");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimProductAuthorization(DesktopAuthTokenHash stateHash, Instant claimedAt) {
        return jdbcClient.sql(CLAIM_PRODUCT_AUTHORIZATION)
                .param("stateHash", stateHash.value())
                .param("claimedAt", utc(claimedAt))
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean issueProductHandoff(
            DesktopAuthTokenHash stateHash,
            DesktopAuthTokenHash handoffCodeHash,
            UUID userId,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return jdbcClient.sql(ISSUE_PRODUCT_HANDOFF)
                .param("stateHash", stateHash.value())
                .param("handoffCodeHash", handoffCodeHash.value())
                .param("userId", userId)
                .param("issuedAt", utc(issuedAt))
                .param("expiresAt", utc(expiresAt))
                .update() == 1;
    }

    @Override
    @Transactional
    public Optional<ProductDesktopAuthExchange> consumeProduct(
            DesktopAuthTokenHash stateHash,
            DesktopAuthTokenHash handoffCodeHash,
            PkceS256Challenge pkceChallenge,
            Instant consumedAt
    ) {
        return jdbcClient.sql(CONSUME_PRODUCT_HANDOFF)
                .param("stateHash", stateHash.value())
                .param("handoffCodeHash", handoffCodeHash.value())
                .param("pkceChallenge", pkceChallenge.value())
                .param("consumedAt", utc(consumedAt))
                .query((resultSet, rowNumber) -> new ProductDesktopAuthExchange(
                        resultSet.getObject("authenticated_user_id", UUID.class),
                        resultSet.getString("client_version"),
                        resultSet.getString("client_protocol_version"),
                        resultSet.getObject("handoff_issued_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("consumed_at", OffsetDateTime.class).toInstant()
                ))
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
