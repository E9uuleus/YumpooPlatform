package com.yumpoo.platform.foundation.testing;

import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
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
import java.util.Map;
import java.util.UUID;

/**
 * 仅供 M0-11 PostgreSQL/HTTP 验收使用，不进入生产制品或 OpenAPI。
 */
@Service
public class M011ProbeApplicationService {

    public static final String CREATE_ROUTE_KEY = "m011CreateProbe";
    public static final String EVENT_TYPE = "foundation.probe_recorded";
    public static final UUID COMPANY_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000111"
    );

    private final JdbcClient jdbcClient;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort transactionalEventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public M011ProbeApplicationService(
            JdbcClient jdbcClient,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort transactionalEventPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.transactionalEventPort = transactionalEventPort;
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
            boolean failAfterEvent
    ) {
        IdempotencyCommand command = new IdempotencyCommand(
                new IdempotencyScope(actorUserId, "POST", CREATE_ROUTE_KEY, idempotencyKey),
                requestHash
        );
        return idempotentCommandExecutor.execute(command, () -> {
            UUID probeId = UUID.randomUUID();
            OffsetDateTime createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int inserted = jdbcClient.sql("""
                            INSERT INTO yumpoo.m011_probe (
                                id, actor_user_id, name, row_version, created_at
                            ) VALUES (
                                :id, :actorUserId, :name, 0, :createdAt
                            )
                            """)
                    .param("id", probeId)
                    .param("actorUserId", actorUserId)
                    .param("name", name)
                    .param("createdAt", createdAt)
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("M0-11 probe insert must affect exactly one row");
            }
            transactionalEventPort.append(probeDraft(
                    EVENT_TYPE,
                    1,
                    probeId,
                    0,
                    actorUserId,
                    name
            ));
            if (failAfterEvent) {
                throw new IllegalStateException("forced M0-11 transaction rollback");
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

    @Transactional
    public DomainEventEnvelope publishOnly(
            String eventType,
            int eventVersion,
            UUID aggregateId,
            long aggregateVersion
    ) {
        return transactionalEventPort.append(probeDraft(
                eventType,
                eventVersion,
                aggregateId,
                aggregateVersion,
                M011ProbeController.FIXED_ACTOR_ID,
                "direct-probe"
        ));
    }

    public EventDraft probeDraft(
            String eventType,
            int eventVersion,
            UUID aggregateId,
            long aggregateVersion,
            UUID actorUserId,
            String name
    ) {
        return new EventDraft(
                eventType,
                eventVersion,
                "M011Probe",
                aggregateId,
                aggregateVersion,
                COMPANY_ID,
                EventActor.user(actorUserId),
                objectMapper.valueToTree(Map.of(
                        "probeId", aggregateId,
                        "name", name
                ))
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("M0-11 probe response serialization failed", exception);
        }
    }

    public record ProbeResponse(UUID id, String name, long rowVersion) {
    }
}
