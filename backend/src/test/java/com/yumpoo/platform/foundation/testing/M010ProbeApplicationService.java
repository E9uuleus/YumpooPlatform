package com.yumpoo.platform.foundation.testing;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.concurrency.ConditionalUpdateFailure;
import com.yumpoo.platform.foundation.application.concurrency.ConditionalUpdateGuard;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅供 M0-10 真实 PostgreSQL 验收使用，不进入生产制品或 OpenAPI。
 */
@Service
public class M010ProbeApplicationService {

    public static final String CREATE_ROUTE_KEY = "m010CreateProbe";

    private final JdbcClient jdbcClient;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public M010ProbeApplicationService(
            JdbcClient jdbcClient,
            IdempotentCommandExecutor idempotentCommandExecutor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public IdempotencyExecutionResult create(
            UUID actorUserId,
            UUID idempotencyKey,
            RequestHash requestHash,
            String name
    ) {
        return create(actorUserId, idempotencyKey, requestHash, name, false);
    }

    public IdempotencyExecutionResult createThenFail(
            UUID actorUserId,
            UUID idempotencyKey,
            RequestHash requestHash,
            String name
    ) {
        return create(actorUserId, idempotencyKey, requestHash, name, true);
    }

    private IdempotencyExecutionResult create(
            UUID actorUserId,
            UUID idempotencyKey,
            RequestHash requestHash,
            String name,
            boolean failAfterInsert
    ) {
        IdempotencyCommand command = new IdempotencyCommand(
                new IdempotencyScope(actorUserId, "POST", CREATE_ROUTE_KEY, idempotencyKey),
                requestHash
        );
        return idempotentCommandExecutor.execute(command, () -> {
            UUID probeId = UUID.randomUUID();
            OffsetDateTime createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int inserted = jdbcClient.sql("""
                            INSERT INTO yumpoo.m010_probe (
                                id, actor_user_id, name, status, row_version, created_at
                            ) VALUES (
                                :id, :actorUserId, :name, 'ACTIVE', 0, :createdAt
                            )
                            """)
                    .param("id", probeId)
                    .param("actorUserId", actorUserId)
                    .param("name", name)
                    .param("createdAt", createdAt)
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("M0-10 probe insert must affect exactly one row");
            }
            if (failAfterInsert) {
                throw new IllegalStateException("forced M0-10 transaction rollback");
            }

            ProbeResponse response = new ProbeResponse(probeId, name, 0);
            return new StoredCommandResult(
                    201,
                    writeJson(response),
                    probeId,
                    IfMatchParser.format(0)
            );
        });
    }

    public boolean isVisible(UUID actorUserId, UUID probeId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM yumpoo.m010_probe
                        WHERE id = :id AND actor_user_id = :actorUserId
                        """)
                .param("id", probeId)
                .param("actorUserId", actorUserId)
                .query(Integer.class)
                .single() == 1;
    }

    @Transactional
    public ProbeResponse update(
            UUID actorUserId,
            UUID probeId,
            long expectedVersion,
            String name
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.m010_probe
                        SET name = :name,
                            row_version = row_version + 1
                        WHERE id = :id
                          AND actor_user_id = :actorUserId
                          AND row_version = :expectedVersion
                          AND status = 'ACTIVE'
                        """)
                .param("name", name)
                .param("id", probeId)
                .param("actorUserId", actorUserId)
                .param("expectedVersion", expectedVersion)
                .update();

        ConditionalUpdateGuard.requireSingleRowUpdated(
                updated,
                () -> classifyFailure(actorUserId, probeId, expectedVersion)
        );
        return findVisible(actorUserId, probeId).orElseThrow();
    }

    public Optional<ProbeResponse> findVisible(UUID actorUserId, UUID probeId) {
        return jdbcClient.sql("""
                        SELECT id, name, row_version
                        FROM yumpoo.m010_probe
                        WHERE id = :id AND actor_user_id = :actorUserId
                        """)
                .param("id", probeId)
                .param("actorUserId", actorUserId)
                .query((resultSet, rowNumber) -> new ProbeResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("name"),
                        resultSet.getLong("row_version")
                ))
                .optional();
    }

    public void insertDirect(UUID actorUserId, UUID probeId, String name, String status, long rowVersion) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.m010_probe (
                            id, actor_user_id, name, status, row_version, created_at
                        ) VALUES (
                            :id, :actorUserId, :name, :status, :rowVersion, :createdAt
                        )
                        """)
                .param("id", probeId)
                .param("actorUserId", actorUserId)
                .param("name", name)
                .param("status", status)
                .param("rowVersion", rowVersion)
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    private ConditionalUpdateFailure classifyFailure(
            UUID actorUserId,
            UUID probeId,
            long expectedVersion
    ) {
        Optional<ProbeState> visibleState = jdbcClient.sql("""
                        SELECT row_version, status
                        FROM yumpoo.m010_probe
                        WHERE id = :id AND actor_user_id = :actorUserId
                        """)
                .param("id", probeId)
                .param("actorUserId", actorUserId)
                .query((resultSet, rowNumber) -> new ProbeState(
                        resultSet.getLong("row_version"),
                        resultSet.getString("status")
                ))
                .optional();

        if (visibleState.isEmpty()) {
            return ConditionalUpdateFailure.RESOURCE_NOT_VISIBLE;
        }
        if (visibleState.get().rowVersion() != expectedVersion) {
            return ConditionalUpdateFailure.VERSION_CONFLICT;
        }
        return ConditionalUpdateFailure.INVALID_STATE;
    }

    private String writeJson(ProbeResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("M0-10 probe response serialization failed", exception);
        }
    }

    public record ProbeResponse(UUID id, String name, long rowVersion) {
    }

    private record ProbeState(long rowVersion, String status) {
    }
}
