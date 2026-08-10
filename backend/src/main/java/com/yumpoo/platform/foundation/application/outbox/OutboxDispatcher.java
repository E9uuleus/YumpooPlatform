package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.OutboxConsumerException;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.foundation.application.logging.StructuredLoggingContext;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class OutboxDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final String DISPATCHER_CONSUMER = "outbox.dispatcher";

    private final OutboxStorePort storePort;
    private final OutboxConsumerRegistry registry;
    private final OutboxConsumerExecutor consumerExecutor;
    private final OutboxRetryPolicy retryPolicy;
    private final OutboxRuntimeSettings settings;
    private final OutboxWorkerIdentity workerIdentity;
    private final OutboxTaskExecutor deliveryExecutor;
    private final Clock clock;

    public OutboxDispatcher(
            OutboxStorePort storePort,
            OutboxConsumerRegistry registry,
            OutboxConsumerExecutor consumerExecutor,
            OutboxRetryPolicy retryPolicy,
            OutboxRuntimeSettings settings,
            OutboxWorkerIdentity workerIdentity,
            OutboxTaskExecutor deliveryExecutor,
            Clock clock
    ) {
        this.storePort = storePort;
        this.registry = registry;
        this.consumerExecutor = consumerExecutor;
        this.retryPolicy = retryPolicy;
        this.settings = settings;
        this.workerIdentity = workerIdentity;
        this.deliveryExecutor = deliveryExecutor;
        this.clock = clock;
    }

    /**
     * 领取并等待一个有限批次处理完成；调度器使用 fixed-delay，批次之间不会重叠。
     */
    public int dispatchOnce() {
        List<OutboxClaim> claims = storePort.claimBatch(
                settings.batchSize(),
                workerIdentity.value(),
                UUID.randomUUID(),
                clock.instant(),
                settings.leaseDuration()
        );
        List<CompletableFuture<Void>> tasks = new ArrayList<>(claims.size());
        for (OutboxClaim claim : claims) {
            tasks.add(deliveryExecutor.submit(() -> process(claim)));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        return claims.size();
    }

    private void process(OutboxClaim claim) {
        DomainEventEnvelope event = claim.event();
        RequestCorrelation consumerCorrelation = new RequestCorrelation(
                event.requestId(),
                event.correlationId(),
                event.eventId()
        );
        try (
                RequestCorrelationContext.Scope ignoredCorrelation =
                        RequestCorrelationContext.open(consumerCorrelation);
                StructuredLoggingContext.Scope ignoredLogging = StructuredLoggingContext.open(Map.of(
                        StructuredLoggingContext.REQUEST_ID, event.requestId(),
                        StructuredLoggingContext.CORRELATION_ID, event.correlationId(),
                        StructuredLoggingContext.EVENT_ID, event.eventId(),
                        StructuredLoggingContext.ATTEMPT, claim.attemptCount()
                ))
        ) {
            List<OutboxEventConsumer> consumers = registry.consumersFor(event);
            if (consumers.isEmpty()) {
                String errorCode = registry.knowsEventType(event.eventType())
                        ? "UNSUPPORTED_EVENT_VERSION"
                        : "NO_MATCHING_CONSUMER";
                fail(claim, new OutboxFailure(
                        DISPATCHER_CONSUMER,
                        errorCode,
                        "ConsumerRegistryFailure",
                        false
                ));
                return;
            }

            for (OutboxEventConsumer consumer : consumers) {
                try (StructuredLoggingContext.Scope ignoredConsumer = StructuredLoggingContext.open(Map.of(
                        StructuredLoggingContext.CONSUMER_NAME, consumer.consumerName()
                ))) {
                    OutboxConsumerOutcome outcome = consumerExecutor.execute(consumer, event);
                    try (StructuredLoggingContext.Scope ignoredOutcome = StructuredLoggingContext.open(Map.of(
                            StructuredLoggingContext.OUTCOME, outcome.name()
                    ))) {
                        LOGGER.info("outbox consumer completed");
                    }
                } catch (OutboxConsumerException exception) {
                    fail(claim, new OutboxFailure(
                            consumer.consumerName(),
                            exception.errorCode(),
                            safeExceptionType(exception),
                            exception.retryable()
                    ));
                    return;
                } catch (RuntimeException exception) {
                    fail(claim, new OutboxFailure(
                            consumer.consumerName(),
                            "UNEXPECTED_CONSUMER_FAILURE",
                            safeExceptionType(exception),
                            true
                    ));
                    return;
                }
            }

            boolean completed = storePort.markCompleted(claim.lease(), clock.instant());
            try (StructuredLoggingContext.Scope ignoredOutcome = StructuredLoggingContext.open(Map.of(
                    StructuredLoggingContext.OUTCOME, completed ? "COMPLETED" : "STALE_LEASE"
            ))) {
                LOGGER.info("outbox event finalized");
            }
        }
    }

    private void fail(OutboxClaim claim, OutboxFailure failure) {
        Instant failedAt = clock.instant();
        boolean retry = retryPolicy.shouldRetry(claim.attemptCount(), failure.retryable());
        boolean updated;
        String outcome;
        if (retry) {
            updated = storePort.markRetry(
                    claim.lease(),
                    failure,
                    retryPolicy.nextAttemptAt(claim.attemptCount(), failedAt)
            );
            outcome = updated ? "RETRY" : "STALE_LEASE";
        } else {
            updated = storePort.markDead(claim.lease(), failure, failedAt);
            outcome = updated ? "DEAD" : "STALE_LEASE";
        }
        try (StructuredLoggingContext.Scope ignoredFailure = StructuredLoggingContext.open(Map.of(
                StructuredLoggingContext.CONSUMER_NAME, failure.consumerName(),
                StructuredLoggingContext.ERROR_CODE, failure.errorCode(),
                StructuredLoggingContext.OUTCOME, outcome
        ))) {
            LOGGER.warn("outbox event processing failed; exceptionType={}", failure.exceptionType());
        }
    }

    private static String safeExceptionType(RuntimeException exception) {
        String name = exception.getClass().getName();
        return name.length() <= 160 ? name : name.substring(0, 160);
    }
}
