package com.yumpoo.platform.foundation.infrastructure.idempotency;

import com.yumpoo.platform.foundation.application.idempotency.IdempotencyClaim;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyRecord;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyRecordPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyState;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRecordRepository implements IdempotencyRecordPort {

    private static final String INSERT_PROCESSING = """
            INSERT INTO yumpoo.idempotency_record (
                id,
                actor_user_id,
                http_method,
                route_key,
                idempotency_key,
                request_hash,
                state,
                lease_until,
                created_at,
                expires_at
            ) VALUES (
                :id,
                :actorUserId,
                :httpMethod,
                :routeKey,
                :idempotencyKey,
                :requestHash,
                'PROCESSING',
                :leaseUntil,
                :createdAt,
                :expiresAt
            )
            ON CONFLICT (actor_user_id, http_method, route_key, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String SELECT_BY_SCOPE = """
            SELECT
                id,
                actor_user_id,
                http_method,
                route_key,
                idempotency_key,
                request_hash,
                state,
                http_status,
                response_text AS response_json,
                resource_id,
                response_etag,
                lease_until,
                created_at,
                completed_at,
                expires_at
            FROM yumpoo.idempotency_record
            WHERE actor_user_id = :actorUserId
              AND http_method = :httpMethod
              AND route_key = :routeKey
              AND idempotency_key = :idempotencyKey
            """;

    private static final String COMPLETE_PROCESSING = """
            UPDATE yumpoo.idempotency_record
            SET state = 'COMPLETED',
                http_status = :httpStatus,
                response_json = CAST(:responseJson AS jsonb),
                response_text = :responseJson,
                resource_id = :resourceId,
                response_etag = :responseEtag,
                lease_until = NULL,
                completed_at = :completedAt,
                expires_at = :expiresAt
            WHERE id = :id
              AND state = 'PROCESSING'
            """;

    private final JdbcClient jdbcClient;

    public JdbcIdempotencyRecordRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public IdempotencyClaim claim(
            UUID recordId,
            IdempotencyCommand command,
            Instant createdAt,
            Instant leaseUntil,
            Instant expiresAt
    ) {
        IdempotencyScope scope = command.scope();
        Optional<UUID> insertedId = jdbcClient.sql(INSERT_PROCESSING)
                .param("id", recordId)
                .param("actorUserId", scope.actorUserId())
                .param("httpMethod", scope.httpMethod())
                .param("routeKey", scope.routeKey())
                .param("idempotencyKey", scope.idempotencyKey())
                .param("requestHash", command.requestHash().value())
                .param("leaseUntil", utc(leaseUntil))
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .query(UUID.class)
                .optional();

        if (insertedId.isPresent()) {
            return new IdempotencyClaim.Acquired(insertedId.get());
        }

        IdempotencyRecord existing = jdbcClient.sql(SELECT_BY_SCOPE)
                .param("actorUserId", scope.actorUserId())
                .param("httpMethod", scope.httpMethod())
                .param("routeKey", scope.routeKey())
                .param("idempotencyKey", scope.idempotencyKey())
                .query(JdbcIdempotencyRecordRepository::mapRecord)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency scope conflicted but the existing record is unavailable"
                ));
        return new IdempotencyClaim.Existing(existing);
    }

    @Override
    public void complete(
            UUID recordId,
            StoredCommandResult result,
            Instant completedAt,
            Instant expiresAt
    ) {
        int updatedRows = jdbcClient.sql(COMPLETE_PROCESSING)
                .param("id", recordId)
                .param("httpStatus", result.httpStatus())
                .param("responseJson", result.responseJson())
                .param("resourceId", result.resourceId())
                .param("responseEtag", result.etag())
                .param("completedAt", utc(completedAt))
                .param("expiresAt", utc(expiresAt))
                .update();
        if (updatedRows != 1) {
            throw new IllegalStateException("processing idempotency record was not completed: " + recordId);
        }
    }

    private static IdempotencyRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        IdempotencyState state = IdempotencyState.valueOf(resultSet.getString("state"));
        StoredCommandResult result = null;
        if (state == IdempotencyState.COMPLETED) {
            result = new StoredCommandResult(
                    resultSet.getInt("http_status"),
                    resultSet.getString("response_json"),
                    resultSet.getObject("resource_id", UUID.class),
                    resultSet.getString("response_etag")
            );
        }
        IdempotencyScope scope = new IdempotencyScope(
                resultSet.getObject("actor_user_id", UUID.class),
                resultSet.getString("http_method"),
                resultSet.getString("route_key"),
                resultSet.getObject("idempotency_key", UUID.class)
        );
        return new IdempotencyRecord(
                resultSet.getObject("id", UUID.class),
                new IdempotencyCommand(scope, new RequestHash(resultSet.getString("request_hash"))),
                state,
                result,
                instantOrNull(resultSet, "lease_until"),
                requiredInstant(resultSet, "created_at"),
                instantOrNull(resultSet, "completed_at"),
                requiredInstant(resultSet, "expires_at")
        );
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant requiredInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException(column + " must not be null");
        }
        return value.toInstant();
    }

    private static Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
