package com.yumpoo.platform.identityaccess.infrastructure.oauth;

import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttempt;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptHash;
import com.yumpoo.platform.identityaccess.application.oauth.OAuthAttemptStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Repository
public class JdbcOAuthAttemptRepository implements OAuthAttemptStore {

    private static final String INSERT_ATTEMPT = """
            INSERT INTO yumpoo.wecom_oauth_attempt (
                state_hash,
                nonce_hash,
                request_id,
                created_at,
                expires_at
            ) VALUES (
                :stateHash,
                :nonceHash,
                :requestId,
                :createdAt,
                :expiresAt
            )
            """;

    private static final String CONSUME_ATTEMPT = """
            UPDATE yumpoo.wecom_oauth_attempt
            SET consumed_at = :consumedAt
            WHERE state_hash = :stateHash
              AND nonce_hash = :nonceHash
              AND consumed_at IS NULL
              AND created_at <= :consumedAt
              AND expires_at > :consumedAt
            """;

    private final JdbcClient jdbcClient;

    public JdbcOAuthAttemptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    @Transactional
    public void create(OAuthAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        int insertedRows = jdbcClient.sql(INSERT_ATTEMPT)
                .param("stateHash", attempt.stateHash().value())
                .param("nonceHash", attempt.nonceHash().value())
                .param("requestId", attempt.requestId())
                .param("createdAt", utc(attempt.createdAt()))
                .param("expiresAt", utc(attempt.expiresAt()))
                .update();
        if (insertedRows != 1) {
            throw new IllegalStateException("OAuth attempt was not created");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(
            OAuthAttemptHash stateHash,
            OAuthAttemptHash nonceHash,
            Instant consumedAt
    ) {
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        Objects.requireNonNull(nonceHash, "nonceHash must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");
        return jdbcClient.sql(CONSUME_ATTEMPT)
                .param("stateHash", stateHash.value())
                .param("nonceHash", nonceHash.value())
                .param("consumedAt", utc(consumedAt))
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
