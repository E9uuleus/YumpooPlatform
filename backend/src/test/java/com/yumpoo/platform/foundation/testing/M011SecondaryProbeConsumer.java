package com.yumpoo.platform.foundation.testing;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxConsumerException;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class M011SecondaryProbeConsumer implements OutboxEventConsumer {

    public static final String NAME = "notification.m011_probe_projection";

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger postInsertFailuresRemaining = new AtomicInteger();
    private final AtomicInteger permanentFailuresRemaining = new AtomicInteger();

    public M011SecondaryProbeConsumer(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Override
    public String consumerName() {
        return NAME;
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        return Set.of(new EventSubscription(M011ProbeApplicationService.EVENT_TYPE, 1));
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        calls.incrementAndGet();
        int remaining = failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1));
        if (remaining > 0) {
            throw new OutboxConsumerException("M011_RETRYABLE_FAILURE", true);
        }
        int permanentRemaining = permanentFailuresRemaining.getAndUpdate(
                value -> Math.max(0, value - 1)
        );
        if (permanentRemaining > 0) {
            throw new OutboxConsumerException("M011_PERMANENT_FAILURE", false);
        }
        RequestCorrelation correlation = RequestCorrelationContext.required();
        jdbcClient.sql("""
                        INSERT INTO yumpoo.m011_projection (
                            consumer_name, event_id, probe_id, request_id,
                            correlation_id, consumer_causation_id, created_at
                        ) VALUES (
                            :consumerName, :eventId, :probeId, :requestId,
                            :correlationId, :causationId, :createdAt
                        )
                        """)
                .param("consumerName", NAME)
                .param("eventId", event.eventId())
                .param("probeId", event.aggregateId())
                .param("requestId", correlation.requestId())
                .param("correlationId", correlation.correlationId())
                .param("causationId", correlation.causationId())
                .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        int postInsertRemaining = postInsertFailuresRemaining.getAndUpdate(
                value -> Math.max(0, value - 1)
        );
        if (postInsertRemaining > 0) {
            throw new OutboxConsumerException("M011_POST_EFFECT_FAILURE", true);
        }
    }

    public void failNext(int count) {
        failuresRemaining.set(count);
    }

    public void failAfterInsertNext(int count) {
        postInsertFailuresRemaining.set(count);
    }

    public void failPermanentlyNext(int count) {
        permanentFailuresRemaining.set(count);
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
        failuresRemaining.set(0);
        postInsertFailuresRemaining.set(0);
        permanentFailuresRemaining.set(0);
    }
}
