package com.yumpoo.platform.identityaccess.infrastructure.event;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerExecutor;
import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerOutcome;
import com.yumpoo.platform.foundation.application.outbox.OutboxConsumerRegistry;
import com.yumpoo.platform.foundation.application.outbox.OutboxDispatcher;
import com.yumpoo.platform.foundation.application.outbox.OutboxFailure;
import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import com.yumpoo.platform.identityaccess.application.event.IdentityCommittedFactConsumer;
import com.yumpoo.platform.testing.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PostgreSqlTestContainerConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "yumpoo.outbox.enabled=false"
)
class IdentityOutboxIT {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("94000000-0000-4000-8000-000000000109");
    private static final UUID PRODUCT_ID = UUID.fromString("95000000-0000-4000-8000-000000000109");
    private static final Set<String> GOVERNANCE_EVENTS = Set.of(
            "identity.user_employment_left", "identity.user_employment_returned",
            "identity.user_account_disabled", "identity.user_account_enabled",
            "identity.app_manager_missing_detected", "identity.app_manager_availability_restored");

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OutboxStorePort store;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private OutboxConsumerRegistry registry;
    @Autowired
    private OutboxConsumerExecutor executor;
    @Autowired
    private IdentityCommittedFactConsumer consumer;
    @Autowired
    private IdentityOutboxRecoveryRunner recovery;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM yumpoo.governance_issue WHERE target_id = :id")
                .param("id", PRODUCT_ID).update();
        jdbc.sql("DELETE FROM yumpoo.product WHERE id = :id").param("id", PRODUCT_ID).update();
        jdbc.sql("DELETE FROM yumpoo.identity_user WHERE id = :id").param("id", USER_ID).update();
        jdbc.sql("TRUNCATE yumpoo.outbox_event, yumpoo.outbox_consumer_receipt").update();
    }

    @Test
    void everyContractedIdentityEventHasAnExactConsumerWithoutMaskingGovernance() throws IOException {
        List<DomainEventEnvelope> examples = identityExamples();
        assertThat(examples).isNotEmpty();
        for (DomainEventEnvelope event : examples) {
            assertThat(registry.consumersFor(event))
                    .as("%s@%s", event.eventType(), event.eventVersion()).isNotEmpty();
            if (GOVERNANCE_EVENTS.contains(event.eventType())) {
                assertThat(registry.consumersFor(event)).doesNotContain(consumer);
            } else {
                assertThat(registry.consumersFor(event)).contains(consumer);
            }
        }
    }

    @Test
    void committedFactsIncludingHistoricalVersionsCompleteWithIdempotentReceipts() throws IOException {
        List<DomainEventEnvelope> facts = new ArrayList<>();
        for (DomainEventEnvelope example : identityExamples()) {
            if (consumer.subscriptions().contains(new EventSubscription(
                    example.eventType(), example.eventVersion()))) {
                DomainEventEnvelope fact = new DomainEventEnvelope(
                        UUID.randomUUID(), example.eventType(), example.eventVersion(),
                        example.occurredAt(), example.aggregateType(), UUID.randomUUID(),
                        example.aggregateVersion(), COMPANY_ID, example.actor(), example.requestId(),
                        example.correlationId(), null, example.payload());
                store.append(fact);
                facts.add(fact);
            }
        }
        assertThat(facts).hasSize(10);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(facts.size());

        for (DomainEventEnvelope fact : facts) {
            assertThat(row(fact)).containsEntry("status", "COMPLETED");
            assertThat(executor.execute(consumer, fact)).isEqualTo(OutboxConsumerOutcome.ALREADY_COMPLETED);
            assertThat(receipts(fact)).containsExactly(consumer.consumerName());
        }
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void startupRecoveryUnblocksTheNextUserVersionAndRunsItsGovernanceProjection() {
        insertProductWithDisabledOwner();
        DomainEventEnvelope revoked = append("identity.user_sessions_revoked", 2, USER_ID, 1);
        markDead(revoked, "outbox.dispatcher", "NO_MATCHING_CONSUMER", "ConsumerRegistryFailure");
        DomainEventEnvelope disabled = append("identity.user_account_disabled", 1, USER_ID, 2);

        assertThat(dispatcher.dispatchOnce()).isZero();
        recover();
        assertThat(row(revoked)).containsEntry("status", "RETRY")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error_code", "NO_MATCHING_CONSUMER")
                .containsEntry("dead_at", null);
        assertThat(receipts(revoked)).isEmpty();

        assertThat(dispatcher.dispatchOnce()).isOne();
        assertThat(row(revoked)).containsEntry("status", "COMPLETED");
        assertThat(row(disabled)).containsEntry("status", "PENDING");
        assertThat(openIssues()).isZero();

        assertThat(dispatcher.dispatchOnce()).isOne();
        assertThat(row(disabled)).containsEntry("status", "COMPLETED");
        assertThat(openIssues()).isOne();
        assertThat(receipts(disabled)).containsExactly(
                "administration-product-owner-governance-v1",
                "administration-project-owner-governance-v1");
        Map<String, Object> completed = row(revoked);
        recover();
        assertThat(row(revoked)).isEqualTo(completed);
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void recoveryOnlyRequeuesExactPairsThatDiedFromMissingConsumerRegistration() throws IOException {
        List<DomainEventEnvelope> recoverable = new ArrayList<>();
        for (DomainEventEnvelope example : identityExamples()) {
            if (consumer.subscriptions().contains(new EventSubscription(
                    example.eventType(), example.eventVersion()))) {
                DomainEventEnvelope event = append(example.eventType(), example.eventVersion(), UUID.randomUUID(), 0);
                markDead(event, "outbox.dispatcher", "NO_MATCHING_CONSUMER", "ConsumerRegistryFailure");
                recoverable.add(event);
            }
        }
        List<DomainEventEnvelope> excluded = new ArrayList<>();
        for (EventSubscription subscription : List.of(
                new EventSubscription("identity.login_succeeded", 99),
                new EventSubscription("identity.unhandled", 1),
                new EventSubscription("foundation.unhandled", 1),
                new EventSubscription("identity.user_account_disabled", 1))) {
            DomainEventEnvelope event = append(subscription.eventType(), subscription.eventVersion(), UUID.randomUUID(), 0);
            markDead(event, "outbox.dispatcher", "NO_MATCHING_CONSUMER", "ConsumerRegistryFailure");
            excluded.add(event);
        }
        for (OutboxFailure failure : List.of(
                new OutboxFailure("identity.other", "NO_MATCHING_CONSUMER", "ConsumerRegistryFailure", false),
                new OutboxFailure("outbox.dispatcher", "UNSUPPORTED_EVENT_VERSION", "ConsumerRegistryFailure", false),
                new OutboxFailure("outbox.dispatcher", "NO_MATCHING_CONSUMER", "OtherFailure", false),
                new OutboxFailure("identity.other", "UNEXPECTED_CONSUMER_FAILURE", "DatabaseFailure", true))) {
            DomainEventEnvelope event = append("identity.login_succeeded", 1, UUID.randomUUID(), 0);
            markDead(event, failure.consumerName(), failure.errorCode(), failure.exceptionType());
            excluded.add(event);
        }
        List<Map<String, Object>> before = excluded.stream().map(this::row).toList();

        recover();

        assertThat(recoverable).hasSize(10).allSatisfy(event ->
                assertThat(row(event)).containsEntry("status", "RETRY").containsEntry("attempt_count", 1));
        assertThat(excluded.stream().map(this::row).toList()).isEqualTo(before);
        List<Map<String, Object>> queued = recoverable.stream().map(this::row).toList();
        recover();
        assertThat(recoverable.stream().map(this::row).toList()).isEqualTo(queued);
    }

    @Test
    void recoveryLeavesPendingRetryProcessingAndCompletedEventsUntouched() {
        DomainEventEnvelope completed = append("identity.login_succeeded", 1, UUID.randomUUID(), 0);
        assertThat(dispatcher.dispatchOnce()).isOne();
        DomainEventEnvelope processing = append("identity.login_succeeded", 1, UUID.randomUUID(), 0);
        assertThat(store.claimBatch(1, "identity-recovery-test", UUID.randomUUID(), Instant.now(),
                Duration.ofMinutes(1))).hasSize(1);
        DomainEventEnvelope retry = append("identity.login_succeeded", 1, UUID.randomUUID(), 0);
        var retryClaim = store.claimBatch(1, "identity-recovery-test", UUID.randomUUID(), Instant.now(),
                Duration.ofMinutes(1)).getFirst();
        assertThat(store.markRetry(retryClaim.lease(),
                new OutboxFailure("identity.other", "TEMPORARY_FAILURE", "DatabaseFailure", true),
                Instant.now().plusSeconds(60))).isTrue();
        DomainEventEnvelope pending = append("identity.login_succeeded", 1, UUID.randomUUID(), 0);
        List<DomainEventEnvelope> events = List.of(completed, processing, retry, pending);
        List<Map<String, Object>> before = events.stream().map(this::row).toList();

        recover();

        assertThat(events.stream().map(this::row).toList()).isEqualTo(before);
        assertThat(receipts(completed)).containsExactly(consumer.consumerName());
    }

    @Test
    void unknownIdentityTypesAndVersionsStillDieAndBlockLaterVersions() {
        DomainEventEnvelope unknown = append("identity.unhandled", 1, UUID.randomUUID(), 0);
        DomainEventEnvelope unsupported = append("identity.login_succeeded", 99, UUID.randomUUID(), 0);
        DomainEventEnvelope afterUnknown = append("identity.user_sessions_revoked", 2, unknown.aggregateId(), 1);
        DomainEventEnvelope afterUnsupported = append("identity.user_sessions_revoked", 2, unsupported.aggregateId(), 1);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(2);

        assertThat(row(unknown)).containsEntry("status", "DEAD")
                .containsEntry("last_error_code", "NO_MATCHING_CONSUMER");
        assertThat(row(unsupported)).containsEntry("status", "DEAD")
                .containsEntry("last_error_code", "UNSUPPORTED_EVENT_VERSION");
        recover();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(row(afterUnknown)).containsEntry("status", "PENDING");
        assertThat(row(afterUnsupported)).containsEntry("status", "PENDING");
    }

    private List<DomainEventEnvelope> identityExamples() throws IOException {
        try (var paths = Files.list(Path.of("../contracts/events/examples"))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("identity."))
                    .filter(path -> path.toString().endsWith(".json") && !path.toString().endsWith(".invalid.json"))
                    .sorted().map(path -> json.readValue(path.toFile(), DomainEventEnvelope.class)).toList();
        }
    }

    private DomainEventEnvelope append(String type, int version, UUID aggregateId, long aggregateVersion) {
        DomainEventEnvelope event = new DomainEventEnvelope(UUID.randomUUID(), type, version,
                Instant.now().minusSeconds(1), "User", aggregateId, aggregateVersion,
                COMPANY_ID, EventActor.system("IDENTITY_OUTBOX_TEST"), "identity-outbox-test",
                "identity-outbox-test", null, json.createObjectNode().put("userId", aggregateId.toString()));
        store.append(event);
        return event;
    }

    private void markDead(DomainEventEnvelope event, String failedConsumer, String code, String exceptionType) {
        jdbc.sql("""
                        UPDATE yumpoo.outbox_event SET status = 'DEAD', attempt_count = 1,
                            next_attempt_at = NULL, dead_at = clock_timestamp(),
                            last_error_consumer = :consumer, last_error_code = :code, last_error_type = :type
                        WHERE event_id = :id
                        """)
                .param("id", event.eventId()).param("consumer", failedConsumer)
                .param("code", code).param("type", exceptionType).update();
    }

    private void recover() {
        recovery.run(new DefaultApplicationArguments());
    }

    private Map<String, Object> row(DomainEventEnvelope event) {
        return jdbc.sql("SELECT * FROM yumpoo.outbox_event WHERE event_id = :id")
                .param("id", event.eventId()).query().singleRow();
    }

    private List<String> receipts(DomainEventEnvelope event) {
        return jdbc.sql("SELECT consumer_name FROM yumpoo.outbox_consumer_receipt "
                        + "WHERE event_id = :id ORDER BY consumer_name")
                .param("id", event.eventId()).query(String.class).list();
    }

    private int openIssues() {
        return jdbc.sql("SELECT count(*) FROM yumpoo.governance_issue "
                        + "WHERE target_id = :id AND status = 'OPEN' AND issue_type = 'OWNER_MISSING'")
                .param("id", PRODUCT_ID).query(Integer.class).single();
    }

    private void insertProductWithDisabledOwner() {
        jdbc.sql("""
                        INSERT INTO yumpoo.identity_user (
                            id, company_id, employment_status, account_status, display_name,
                            directory_synced_at, authorization_version, row_version, created_at, updated_at
                        ) VALUES (:id, :companyId, 'ACTIVE', 'ENABLED', 'Identity Outbox Owner',
                            transaction_timestamp(), 0, 0, transaction_timestamp(), transaction_timestamp())
                        """).param("id", USER_ID).param("companyId", COMPANY_ID).update();
        jdbc.sql("""
                        UPDATE yumpoo.identity_user SET account_status = 'DISABLED',
                            account_disabled_at = transaction_timestamp(), account_disabled_by_user_id = :id,
                            account_disabled_reason = 'identity outbox regression',
                            updated_at = transaction_timestamp(), row_version = row_version + 1
                        WHERE id = :id
                        """).param("id", USER_ID).update();
        jdbc.sql("""
                        INSERT INTO yumpoo.product (
                            id, company_id, product_code, name, status, owner_user_id,
                            row_version, created_at, created_by_user_id, updated_at, updated_by_user_id
                        ) VALUES (:id, :companyId, 'IDENTITY_OUTBOX', 'Identity Outbox', 'ACTIVE', :ownerId,
                            0, transaction_timestamp(), :ownerId, transaction_timestamp(), :ownerId)
                        """).param("id", PRODUCT_ID).param("companyId", COMPANY_ID)
                .param("ownerId", USER_ID).update();
    }
}
