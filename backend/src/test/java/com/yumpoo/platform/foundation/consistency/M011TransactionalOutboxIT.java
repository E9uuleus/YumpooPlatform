package com.yumpoo.platform.foundation.consistency;

import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.OutboxConsumerException;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.outbox.OutboxClaim;
import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerExecutor;
import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerOutcome;
import com.yumpoo.platform.foundation.application.outbox.OutboxDispatcher;
import com.yumpoo.platform.foundation.application.outbox.OutboxFailure;
import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import com.yumpoo.platform.foundation.application.request.RequestIdContext;
import com.yumpoo.platform.foundation.testing.M011PrimaryProbeConsumer;
import com.yumpoo.platform.foundation.testing.M011ProbeApplicationService;
import com.yumpoo.platform.foundation.testing.M011ProbeController;
import com.yumpoo.platform.foundation.testing.M011SecondaryProbeConsumer;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import com.yumpoo.platform.testing.TestProbeSecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        PostgreSqlTestContainerConfiguration.class,
        TestProbeSecurityConfiguration.class,
        M011ProbeApplicationService.class,
        M011ProbeController.class,
        M011PrimaryProbeConsumer.class,
        M011SecondaryProbeConsumer.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yumpoo.outbox.enabled=false"
)
@Sql(
        scripts = "/sql/m0-11-probe-schema.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@Sql(
        scripts = "/sql/m0-11-probe-drop.sql",
        executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS
)
class M011TransactionalOutboxIT {

    private static final String PROBE_PATH = "/api/v1/__test/m0-11/probes";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyRequestHasher requestHasher;

    @Autowired
    private M011ProbeApplicationService applicationService;

    @Autowired
    private TransactionalEventPort transactionalEventPort;

    @Autowired
    private OutboxStorePort outboxStorePort;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private OutboxConsumerExecutor consumerExecutor;

    @Autowired
    private M011PrimaryProbeConsumer primaryConsumer;

    @Autowired
    private M011SecondaryProbeConsumer secondaryConsumer;

    @BeforeEach
    void resetFactsAndConsumers() {
        jdbcClient.sql("""
                        TRUNCATE TABLE
                            yumpoo.m011_projection,
                            yumpoo.outbox_consumer_receipt,
                            yumpoo.outbox_event,
                            yumpoo.m011_probe,
                            yumpoo.idempotency_record
                        """)
                .update();
        primaryConsumer.reset();
        secondaryConsumer.reset();
    }

    @Test
    void httpRequestCorrelationPersistsAndFansOutToTwoIdempotentConsumers() throws Exception {
        String requestId = "m011-http-chain";

        HttpResponse<String> response = postProbe(requestId, UUID.randomUUID(), "http-probe");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue(RequestIdContext.HEADER_NAME)).contains(requestId);
        UUID eventId = singleEventId();
        Map<String, Object> stored = eventRow(eventId);
        assertThat(stored.get("request_id")).isEqualTo(requestId);
        assertThat(stored.get("correlation_id")).isEqualTo(requestId);
        assertThat(stored.get("causation_id")).isNull();
        assertThat(stored.get("status")).isEqualTo("PENDING");

        assertThat(dispatcher.dispatchOnce()).isOne();

        assertThat(eventStatus(eventId)).isEqualTo("COMPLETED");
        assertThat(count("outbox_consumer_receipt")).isEqualTo(2);
        assertThat(count("m011_projection")).isEqualTo(2);
        List<Map<String, Object>> projections = jdbcClient.sql("""
                        SELECT request_id, correlation_id, consumer_causation_id
                        FROM yumpoo.m011_projection
                        ORDER BY consumer_name
                        """)
                .query()
                .listOfRows();
        assertThat(projections).allSatisfy(projection -> {
            assertThat(projection.get("request_id")).isEqualTo(requestId);
            assertThat(projection.get("correlation_id")).isEqualTo(requestId);
            assertThat(projection.get("consumer_causation_id")).isEqualTo(eventId);
        });
    }

    @Test
    void derivedEventInheritsCorrelationAndUsesParentEventAsCausation() {
        DomainEventEnvelope parent = publishOnly(
                "m011-derived-chain",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                UUID.randomUUID(),
                0
        );
        DomainEventEnvelope derived;
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                new RequestCorrelation(
                        parent.requestId(),
                        parent.correlationId(),
                        parent.eventId()
                )
        )) {
            derived = applicationService.publishOnly(
                    M011ProbeApplicationService.EVENT_TYPE,
                    1,
                    UUID.randomUUID(),
                    0
            );
        }

        assertThat(derived.requestId()).isEqualTo(parent.requestId());
        assertThat(derived.correlationId()).isEqualTo(parent.correlationId());
        assertThat(derived.causationId()).isEqualTo(parent.eventId());
    }

    @Test
    void rollbackRemovesBusinessFactIdempotencyClaimAndOutboxEventTogether() {
        RequestHash requestHash = requestHashFor("rollback");

        assertThatThrownBy(() -> withRoot("m011-rollback", () -> applicationService.createThenFail(
                M011ProbeController.FIXED_ACTOR_ID,
                UUID.randomUUID(),
                requestHash,
                "rollback"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("forced M0-11 transaction rollback");

        assertThat(count("m011_probe")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void publishingOutsideAnExistingTransactionFailsClosed() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> withRoot("m011-no-transaction", () ->
                transactionalEventPort.append(applicationService.probeDraft(
                        M011ProbeApplicationService.EVENT_TYPE,
                        1,
                        aggregateId,
                        0,
                        M011ProbeController.FIXED_ACTOR_ID,
                        "no-transaction"
                ))
        )).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void publishingWithoutCorrelationContextRollsBackTheTransaction() {
        assertThatThrownBy(() -> applicationService.publishOnly(
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                UUID.randomUUID(),
                0
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("request correlation context is required");
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void sameAggregateVersionAndEventTypeCannotBePublishedTwice() {
        UUID aggregateId = UUID.randomUUID();
        publishOnly(
                "m011-unique-fact-first",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                0
        );

        assertThatThrownBy(() -> publishOnly(
                "m011-unique-fact-second",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                0
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("outbox_event")).isOne();
    }

    @Test
    void retryKeepsCompletedReceiptAndOnlyInvokesTheRemainingConsumer() throws Exception {
        secondaryConsumer.failNext(1);
        postProbe("m011-retry", UUID.randomUUID(), "retry-probe");
        UUID eventId = singleEventId();

        assertThat(dispatcher.dispatchOnce()).isOne();

        assertThat(eventStatus(eventId)).isEqualTo("RETRY");
        assertThat(primaryConsumer.calls()).isOne();
        assertThat(secondaryConsumer.calls()).isOne();
        assertThat(count("outbox_consumer_receipt")).isOne();
        assertThat(count("m011_projection")).isOne();
        assertThat(eventRow(eventId).get("last_error_code"))
                .isEqualTo("M011_RETRYABLE_FAILURE");
        assertThat(eventRow(eventId).get("last_error_type"))
                .isEqualTo(OutboxConsumerException.class.getName());

        makeRetryDue(eventId);
        assertThat(dispatcher.dispatchOnce()).isOne();

        assertThat(eventStatus(eventId)).isEqualTo("COMPLETED");
        assertThat(primaryConsumer.calls()).isOne();
        assertThat(secondaryConsumer.calls()).isEqualTo(2);
        assertThat(count("outbox_consumer_receipt")).isEqualTo(2);
        assertThat(count("m011_projection")).isEqualTo(2);
    }

    @Test
    void sixthRetryableFailureMovesEventToDeadWithoutPersistingExceptionText() throws Exception {
        secondaryConsumer.failNext(10);
        postProbe("m011-dead", UUID.randomUUID(), "secret-do-not-persist");
        UUID eventId = singleEventId();

        for (int attempt = 1; attempt <= 6; attempt++) {
            if (attempt > 1) {
                makeRetryDue(eventId);
            }
            assertThat(dispatcher.dispatchOnce()).isOne();
            assertThat(eventRow(eventId).get("attempt_count")).isEqualTo(attempt);
            assertThat(eventStatus(eventId)).isEqualTo(attempt <= 5 ? "RETRY" : "DEAD");
        }

        Map<String, Object> dead = eventRow(eventId);
        assertThat(dead.get("last_error_code")).isEqualTo("M011_RETRYABLE_FAILURE");
        assertThat(dead.get("last_error_consumer"))
                .isEqualTo(M011SecondaryProbeConsumer.NAME);
        assertThat(dead.get("last_error_type"))
                .isEqualTo(OutboxConsumerException.class.getName());
        assertThat(dead.values()).doesNotContain("secret-do-not-persist");
        assertThat(primaryConsumer.calls()).isOne();
        assertThat(secondaryConsumer.calls()).isEqualTo(6);
    }

    @Test
    void concurrentAndRepeatedConsumptionCreateOnlyOneEffectAndReceipt() throws Exception {
        DomainEventEnvelope event = publishOnly(
                "m011-consumer-dedup",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                UUID.randomUUID(),
                0
        );

        List<OutboxConsumerOutcome> outcomes = runConcurrently(List.of(
                () -> consumeWithEventContext(primaryConsumer, event),
                () -> consumeWithEventContext(primaryConsumer, event)
        ));

        assertThat(outcomes).containsExactlyInAnyOrder(
                OutboxConsumerOutcome.CONSUMED,
                OutboxConsumerOutcome.ALREADY_COMPLETED
        );
        assertThat(consumeWithEventContext(primaryConsumer, event))
                .isEqualTo(OutboxConsumerOutcome.ALREADY_COMPLETED);
        assertThat(primaryConsumer.calls()).isOne();
        assertThat(count("outbox_consumer_receipt")).isOne();
        assertThat(count("m011_projection")).isOne();
    }

    @Test
    void consumerEffectAndReceiptRollBackTogether() {
        DomainEventEnvelope event = publishOnly(
                "m011-consumer-rollback",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                UUID.randomUUID(),
                0
        );
        secondaryConsumer.failAfterInsertNext(1);

        assertThatThrownBy(() -> consumeWithEventContext(secondaryConsumer, event))
                .isInstanceOf(OutboxConsumerException.class)
                .hasMessage("M011_POST_EFFECT_FAILURE");

        assertThat(count("outbox_consumer_receipt")).isZero();
        assertThat(count("m011_projection")).isZero();
        assertThat(consumeWithEventContext(secondaryConsumer, event))
                .isEqualTo(OutboxConsumerOutcome.CONSUMED);
        assertThat(count("outbox_consumer_receipt")).isOne();
        assertThat(count("m011_projection")).isOne();
    }

    @Test
    void explicitPermanentConsumerFailureIsDeadOnFirstAttempt() throws Exception {
        secondaryConsumer.failPermanentlyNext(1);
        postProbe("m011-permanent", UUID.randomUUID(), "permanent-probe");
        UUID eventId = singleEventId();

        assertThat(dispatcher.dispatchOnce()).isOne();

        Map<String, Object> dead = eventRow(eventId);
        assertThat(dead.get("status")).isEqualTo("DEAD");
        assertThat(dead.get("attempt_count")).isEqualTo(1);
        assertThat(dead.get("last_error_code")).isEqualTo("M011_PERMANENT_FAILURE");
        assertThat(count("outbox_consumer_receipt")).isOne();
        assertThat(count("m011_projection")).isOne();
    }

    @Test
    void missingConsumerAndUnsupportedVersionBecomeObservablePermanentFailures() {
        DomainEventEnvelope missing = publishOnly(
                "m011-missing-consumer",
                "foundation.unhandled",
                1,
                UUID.randomUUID(),
                0
        );
        DomainEventEnvelope unsupported = publishOnly(
                "m011-unsupported-version",
                M011ProbeApplicationService.EVENT_TYPE,
                2,
                UUID.randomUUID(),
                0
        );

        assertThat(dispatcher.dispatchOnce()).isEqualTo(2);

        assertThat(eventStatus(missing.eventId())).isEqualTo("DEAD");
        assertThat(eventRow(missing.eventId()).get("last_error_code"))
                .isEqualTo("NO_MATCHING_CONSUMER");
        assertThat(eventStatus(unsupported.eventId())).isEqualTo("DEAD");
        assertThat(eventRow(unsupported.eventId()).get("last_error_code"))
                .isEqualTo("UNSUPPORTED_EVENT_VERSION");
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotFinalize() {
        DomainEventEnvelope event = publishOnly(
                "m011-lease",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                UUID.randomUUID(),
                0
        );
        Instant firstClaimAt = Instant.now();
        OutboxClaim first = outboxStorePort.claimBatch(
                1,
                "worker-first",
                UUID.randomUUID(),
                firstClaimAt,
                Duration.ofSeconds(1)
        ).getFirst();

        OutboxClaim second = outboxStorePort.claimBatch(
                1,
                "worker-second",
                UUID.randomUUID(),
                firstClaimAt.plusSeconds(2),
                Duration.ofMinutes(5)
        ).getFirst();

        assertThat(second.event().eventId()).isEqualTo(event.eventId());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(outboxStorePort.markCompleted(first.lease(), firstClaimAt.plusSeconds(3)))
                .isFalse();
        assertThat(outboxStorePort.markRetry(
                first.lease(),
                new OutboxFailure(
                        "outbox.dispatcher",
                        "STALE_LEASE_TEST",
                        "StaleLeaseTestFailure",
                        true
                ),
                firstClaimAt.plusSeconds(60)
        )).isFalse();
        assertThat(outboxStorePort.markDead(
                first.lease(),
                new OutboxFailure(
                        "outbox.dispatcher",
                        "STALE_LEASE_TEST",
                        "StaleLeaseTestFailure",
                        false
                ),
                firstClaimAt.plusSeconds(3)
        )).isFalse();
        assertThat(outboxStorePort.markCompleted(second.lease(), firstClaimAt.plusSeconds(3)))
                .isTrue();
    }

    @Test
    void concurrentWorkersNeverClaimTheSameEvent() throws Exception {
        for (int index = 0; index < 8; index++) {
            publishOnly(
                    "m011-concurrent-" + index,
                    M011ProbeApplicationService.EVENT_TYPE,
                    1,
                    UUID.randomUUID(),
                    0
            );
        }
        Instant claimedAt = Instant.now();

        List<List<OutboxClaim>> batches = runConcurrently(List.of(
                () -> outboxStorePort.claimBatch(
                        8, "worker-a", UUID.randomUUID(), claimedAt, Duration.ofMinutes(5)
                ),
                () -> outboxStorePort.claimBatch(
                        8, "worker-b", UUID.randomUUID(), claimedAt, Duration.ofMinutes(5)
                )
        ));

        List<UUID> claimedIds = batches.stream()
                .flatMap(List::stream)
                .map(claim -> claim.event().eventId())
                .toList();
        assertThat(claimedIds).hasSize(8);
        assertThat(new HashSet<>(claimedIds)).hasSize(8);
    }

    @Test
    void deadLowerAggregateVersionStrictlyBlocksLaterVersions() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope first = publishOnly(
                "m011-order-first",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                0
        );
        publishOnly(
                "m011-order-second",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                1
        );
        Instant now = Instant.now();
        List<OutboxClaim> initial = outboxStorePort.claimBatch(
                10,
                "worker-order",
                UUID.randomUUID(),
                now,
                Duration.ofMinutes(5)
        );

        assertThat(initial).singleElement()
                .extracting(claim -> claim.event().eventId())
                .isEqualTo(first.eventId());
        assertThat(outboxStorePort.markDead(
                initial.getFirst().lease(),
                new OutboxFailure(
                        "outbox.dispatcher",
                        "ORDERING_TEST_DEAD",
                        "OrderingTestFailure",
                        false
                ),
                now.plusSeconds(1)
        )).isTrue();

        assertThat(outboxStorePort.claimBatch(
                10,
                "worker-order-later",
                UUID.randomUUID(),
                now.plusSeconds(2),
                Duration.ofMinutes(5)
        )).isEmpty();
    }

    @Test
    void retryingLowerAggregateVersionStrictlyBlocksLaterVersions() {
        UUID aggregateId = UUID.randomUUID();
        DomainEventEnvelope first = publishOnly(
                "m011-retry-order-first",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                0
        );
        publishOnly(
                "m011-retry-order-second",
                M011ProbeApplicationService.EVENT_TYPE,
                1,
                aggregateId,
                1
        );
        Instant now = Instant.now();
        OutboxClaim firstClaim = outboxStorePort.claimBatch(
                10,
                "worker-retry-order",
                UUID.randomUUID(),
                now,
                Duration.ofMinutes(5)
        ).getFirst();
        assertThat(firstClaim.event().eventId()).isEqualTo(first.eventId());
        assertThat(outboxStorePort.markRetry(
                firstClaim.lease(),
                new OutboxFailure(
                        "outbox.dispatcher",
                        "ORDERING_TEST_RETRY",
                        "OrderingTestFailure",
                        true
                ),
                now.plusSeconds(60)
        )).isTrue();

        assertThat(outboxStorePort.claimBatch(
                10,
                "worker-retry-order-early",
                UUID.randomUUID(),
                now.plusSeconds(30),
                Duration.ofMinutes(5)
        )).isEmpty();
        assertThat(outboxStorePort.claimBatch(
                10,
                "worker-retry-order-due",
                UUID.randomUUID(),
                now.plusSeconds(61),
                Duration.ofMinutes(5)
        )).singleElement()
                .extracting(claim -> claim.event().eventId())
                .isEqualTo(first.eventId());
    }

    private HttpResponse<String> postProbe(
            String requestId,
            UUID idempotencyKey,
            String name
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + PROBE_PATH))
                .header(RequestIdContext.HEADER_NAME, requestId)
                .header(IdempotencyKeyParser.HEADER_NAME, idempotencyKey.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("name", name)),
                        StandardCharsets.UTF_8
                ))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private DomainEventEnvelope publishOnly(
            String requestId,
            String eventType,
            int eventVersion,
            UUID aggregateId,
            long aggregateVersion
    ) {
        return withRoot(requestId, () -> applicationService.publishOnly(
                eventType,
                eventVersion,
                aggregateId,
                aggregateVersion
        ));
    }

    private RequestHash requestHashFor(String name) {
        return requestHasher.hash(
                M011ProbeApplicationService.CREATE_ROUTE_KEY,
                Map.of(),
                objectMapper.valueToTree(Map.of("name", name))
        );
    }

    private OutboxConsumerOutcome consumeWithEventContext(
            OutboxEventConsumer consumer,
            DomainEventEnvelope event
    ) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                new RequestCorrelation(
                        event.requestId(),
                        event.correlationId(),
                        event.eventId()
                )
        )) {
            return consumerExecutor.execute(consumer, event);
        }
    }

    private UUID singleEventId() {
        return jdbcClient.sql("SELECT event_id FROM yumpoo.outbox_event")
                .query(UUID.class)
                .single();
    }

    private Map<String, Object> eventRow(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT request_id, correlation_id, causation_id, status,
                               attempt_count, last_error_consumer, last_error_code,
                               last_error_type
                        FROM yumpoo.outbox_event
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query()
                .singleRow();
    }

    private String eventStatus(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT status
                        FROM yumpoo.outbox_event
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private long count(String tableName) {
        Set<String> allowed = Set.of(
                "m011_probe",
                "m011_projection",
                "idempotency_record",
                "outbox_event",
                "outbox_consumer_receipt"
        );
        if (!allowed.contains(tableName)) {
            throw new IllegalArgumentException("unexpected table: " + tableName);
        }
        return jdbcClient.sql("SELECT count(*) FROM yumpoo." + tableName)
                .query(Long.class)
                .single();
    }

    private void makeRetryDue(UUID eventId) {
        int updated = jdbcClient.sql("""
                        UPDATE yumpoo.outbox_event
                        SET next_attempt_at = occurred_at
                        WHERE event_id = :eventId AND status = 'RETRY'
                        """)
                .param("eventId", eventId)
                .update();
        assertThat(updated).isOne();
    }

    private static <T> T withRoot(String requestId, Supplier<T> callback) {
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(
                RequestCorrelation.root(requestId)
        )) {
            return callback.get();
        }
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent test start timed out");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
