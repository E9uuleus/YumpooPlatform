package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxConsumerRegistryTest {

    @Test
    void exactSubscriptionCanFanOutInStableConsumerNameOrder() {
        OutboxEventConsumer second = consumer("notification.second", 1);
        OutboxEventConsumer first = consumer("audit.first", 1);
        OutboxConsumerRegistry registry = new OutboxConsumerRegistry(List.of(second, first));

        assertThat(registry.consumersFor(event(1)))
                .extracting(OutboxEventConsumer::consumerName)
                .containsExactly("audit.first", "notification.second");
        assertThat(registry.knowsEventType("foundation.probe_recorded")).isTrue();
        assertThat(registry.consumersFor(event(2))).isEmpty();
    }

    @Test
    void duplicateConsumerNamesFailApplicationStartup() {
        assertThatThrownBy(() -> new OutboxConsumerRegistry(List.of(
                consumer("audit.duplicate", 1),
                consumer("audit.duplicate", 2)
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate outbox consumer name");
    }

    private static OutboxEventConsumer consumer(String name, int version) {
        return new OutboxEventConsumer() {
            @Override
            public String consumerName() {
                return name;
            }

            @Override
            public Set<EventSubscription> subscriptions() {
                return Set.of(new EventSubscription("foundation.probe_recorded", version));
            }

            @Override
            public void consume(DomainEventEnvelope event) {
            }
        };
    }

    private static DomainEventEnvelope event(int version) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                "foundation.probe_recorded",
                version,
                Instant.parse("2026-08-10T03:00:00Z"),
                "M011Probe",
                UUID.randomUUID(),
                0,
                UUID.randomUUID(),
                EventActor.user(UUID.randomUUID()),
                "m011-registry",
                "m011-registry",
                null,
                JsonNodeFactory.instance.objectNode()
        );
    }
}
