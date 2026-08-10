package com.yumpoo.platform.foundation.infrastructure.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventActorType;
import com.yumpoo.platform.foundation.application.outbox.OutboxClaim;
import com.yumpoo.platform.foundation.application.outbox.OutboxFailure;
import com.yumpoo.platform.foundation.application.outbox.OutboxLease;
import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOutboxRepository implements OutboxStorePort {

    private static final String INSERT_EVENT = """
            INSERT INTO yumpoo.outbox_event (
                event_id, event_type, event_version,
                aggregate_type, aggregate_id, aggregate_version,
                company_id, actor_type, actor_user_id, actor_system_code,
                actor_reason_reference, occurred_at, request_id, correlation_id,
                causation_id, payload_json, status, attempt_count,
                next_attempt_at, created_at
            ) VALUES (
                :eventId, :eventType, :eventVersion,
                :aggregateType, :aggregateId, :aggregateVersion,
                :companyId, :actorType, :actorUserId, :actorSystemCode,
                :actorReasonReference, :occurredAt, :requestId, :correlationId,
                :causationId, CAST(:payloadJson AS jsonb), 'PENDING', 0,
                :occurredAt, :occurredAt
            )
            """;

    private static final String CLAIM_BATCH = """
            WITH candidates AS (
                SELECT candidate.event_id
                FROM yumpoo.outbox_event candidate
                WHERE (
                    (
                        candidate.status IN ('PENDING', 'RETRY')
                        AND candidate.next_attempt_at <= :claimedAt
                    )
                    OR (
                        candidate.status = 'PROCESSING'
                        AND candidate.lease_until <= :claimedAt
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM yumpoo.outbox_event earlier
                    WHERE earlier.aggregate_type = candidate.aggregate_type
                      AND earlier.aggregate_id = candidate.aggregate_id
                      AND earlier.aggregate_version < candidate.aggregate_version
                      AND earlier.status <> 'COMPLETED'
                )
                ORDER BY candidate.occurred_at, candidate.event_id
                FOR UPDATE OF candidate SKIP LOCKED
                LIMIT :batchSize
            )
            UPDATE yumpoo.outbox_event claimed
            SET status = 'PROCESSING',
                attempt_count = claimed.attempt_count + 1,
                next_attempt_at = NULL,
                lease_owner = :leaseOwner,
                lease_token = :leaseToken,
                lease_until = :leaseUntil,
                completed_at = NULL,
                dead_at = NULL
            FROM candidates
            WHERE claimed.event_id = candidates.event_id
            RETURNING
                claimed.event_id, claimed.event_type, claimed.event_version,
                claimed.aggregate_type, claimed.aggregate_id, claimed.aggregate_version,
                claimed.company_id, claimed.actor_type, claimed.actor_user_id,
                claimed.actor_system_code, claimed.actor_reason_reference,
                claimed.occurred_at, claimed.request_id, claimed.correlation_id,
                claimed.causation_id, claimed.payload_json::text AS payload_json,
                claimed.attempt_count, claimed.lease_owner, claimed.lease_token,
                claimed.lease_until
            """;

    private static final String COMPLETE = """
            UPDATE yumpoo.outbox_event
            SET status = 'COMPLETED',
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                last_error_consumer = NULL,
                last_error_code = NULL,
                last_error_type = NULL,
                completed_at = :completedAt,
                dead_at = NULL
            WHERE event_id = :eventId
              AND status = 'PROCESSING'
              AND lease_owner = :leaseOwner
              AND lease_token = :leaseToken
            """;

    private static final String RETRY = """
            UPDATE yumpoo.outbox_event
            SET status = 'RETRY',
                next_attempt_at = :nextAttemptAt,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                last_error_consumer = :consumerName,
                last_error_code = :errorCode,
                last_error_type = :exceptionType,
                completed_at = NULL,
                dead_at = NULL
            WHERE event_id = :eventId
              AND status = 'PROCESSING'
              AND lease_owner = :leaseOwner
              AND lease_token = :leaseToken
            """;

    private static final String DEAD = """
            UPDATE yumpoo.outbox_event
            SET status = 'DEAD',
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_token = NULL,
                lease_until = NULL,
                last_error_consumer = :consumerName,
                last_error_code = :errorCode,
                last_error_type = :exceptionType,
                completed_at = NULL,
                dead_at = :deadAt
            WHERE event_id = :eventId
              AND status = 'PROCESSING'
              AND lease_owner = :leaseOwner
              AND lease_token = :leaseToken
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcOutboxRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(DomainEventEnvelope event) {
        EventActor actor = event.actor();
        int inserted = jdbcClient.sql(INSERT_EVENT)
                .param("eventId", event.eventId())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("companyId", event.companyId())
                .param("actorType", actor.type().name())
                .param("actorUserId", actor.userId())
                .param("actorSystemCode", actor.systemCode())
                .param("actorReasonReference", actor.reasonReference())
                .param("occurredAt", utc(event.occurredAt()))
                .param("requestId", event.requestId())
                .param("correlationId", event.correlationId())
                .param("causationId", event.causationId())
                .param("payloadJson", event.payload().toString())
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("outbox event insert must affect exactly one row");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxClaim> claimBatch(
            int batchSize,
            String leaseOwner,
            UUID leaseToken,
            Instant claimedAt,
            Duration leaseDuration
    ) {
        Instant leaseUntil = claimedAt.plus(leaseDuration);
        List<OutboxClaim> claims = jdbcClient.sql(CLAIM_BATCH)
                .param("claimedAt", utc(claimedAt))
                .param("batchSize", batchSize)
                .param("leaseOwner", leaseOwner)
                .param("leaseToken", leaseToken)
                .param("leaseUntil", utc(leaseUntil))
                .query(this::mapClaim)
                .list();
        return claims.stream()
                .sorted(Comparator
                        .comparing((OutboxClaim claim) -> claim.event().occurredAt())
                        .thenComparing(claim -> claim.event().eventId()))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markCompleted(OutboxLease lease, Instant completedAt) {
        return jdbcClient.sql(COMPLETE)
                .param("completedAt", utc(completedAt))
                .param("eventId", lease.eventId())
                .param("leaseOwner", lease.leaseOwner())
                .param("leaseToken", lease.leaseToken())
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRetry(
            OutboxLease lease,
            OutboxFailure failure,
            Instant nextAttemptAt
    ) {
        return jdbcClient.sql(RETRY)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("consumerName", failure.consumerName())
                .param("errorCode", failure.errorCode())
                .param("exceptionType", failure.exceptionType())
                .param("eventId", lease.eventId())
                .param("leaseOwner", lease.leaseOwner())
                .param("leaseToken", lease.leaseToken())
                .update() == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDead(OutboxLease lease, OutboxFailure failure, Instant deadAt) {
        return jdbcClient.sql(DEAD)
                .param("deadAt", utc(deadAt))
                .param("consumerName", failure.consumerName())
                .param("errorCode", failure.errorCode())
                .param("exceptionType", failure.exceptionType())
                .param("eventId", lease.eventId())
                .param("leaseOwner", lease.leaseOwner())
                .param("leaseToken", lease.leaseToken())
                .update() == 1;
    }

    private OutboxClaim mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID eventId = resultSet.getObject("event_id", UUID.class);
        EventActor actor = mapActor(resultSet);
        DomainEventEnvelope event = new DomainEventEnvelope(
                eventId,
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                requiredInstant(resultSet, "occurred_at"),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                resultSet.getObject("company_id", UUID.class),
                actor,
                resultSet.getString("request_id"),
                resultSet.getString("correlation_id"),
                resultSet.getObject("causation_id", UUID.class),
                readPayload(resultSet.getString("payload_json"))
        );
        OutboxLease lease = new OutboxLease(
                eventId,
                resultSet.getString("lease_owner"),
                resultSet.getObject("lease_token", UUID.class)
        );
        return new OutboxClaim(
                event,
                resultSet.getInt("attempt_count"),
                lease,
                requiredInstant(resultSet, "lease_until")
        );
    }

    private JsonNode readPayload(String json) throws SQLException {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new SQLException("persisted outbox payload is invalid JSON", exception);
        }
    }

    private static EventActor mapActor(ResultSet resultSet) throws SQLException {
        EventActorType type = EventActorType.valueOf(resultSet.getString("actor_type"));
        return switch (type) {
            case USER -> EventActor.user(resultSet.getObject("actor_user_id", UUID.class));
            case SYSTEM -> EventActor.system(resultSet.getString("actor_system_code"));
            case ADMIN_OVERRIDE -> EventActor.adminOverride(
                    resultSet.getObject("actor_user_id", UUID.class),
                    resultSet.getString("actor_reason_reference")
            );
        };
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
}
