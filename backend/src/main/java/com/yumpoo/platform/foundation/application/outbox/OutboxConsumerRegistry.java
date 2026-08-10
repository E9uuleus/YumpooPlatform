package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OutboxConsumerRegistry {

    private static final Pattern CONSUMER_NAME = Pattern.compile("^[a-z][a-z0-9_.:-]{0,119}$");

    private final Map<EventSubscription, List<OutboxEventConsumer>> bySubscription;
    private final Set<String> knownEventTypes;

    public OutboxConsumerRegistry(List<OutboxEventConsumer> consumers) {
        Map<EventSubscription, List<OutboxEventConsumer>> subscriptions = new HashMap<>();
        Set<String> names = new HashSet<>();
        Set<String> eventTypes = new HashSet<>();
        for (OutboxEventConsumer consumer : consumers) {
            String name = consumer.consumerName();
            if (name == null || !CONSUMER_NAME.matcher(name).matches()) {
                throw new IllegalStateException("invalid outbox consumer name: " + name);
            }
            if (!names.add(name)) {
                throw new IllegalStateException("duplicate outbox consumer name: " + name);
            }
            Set<EventSubscription> declared = consumer.subscriptions();
            if (declared == null || declared.isEmpty()) {
                throw new IllegalStateException("outbox consumer has no subscriptions: " + name);
            }
            for (EventSubscription subscription : Set.copyOf(declared)) {
                eventTypes.add(subscription.eventType());
                subscriptions.computeIfAbsent(subscription, ignored -> new ArrayList<>()).add(consumer);
            }
        }
        subscriptions.values().forEach(list -> list.sort(
                Comparator.comparing(OutboxEventConsumer::consumerName)
        ));
        this.bySubscription = Map.copyOf(subscriptions);
        this.knownEventTypes = Set.copyOf(eventTypes);
    }

    public List<OutboxEventConsumer> consumersFor(DomainEventEnvelope event) {
        return bySubscription.getOrDefault(
                new EventSubscription(event.eventType(), event.eventVersion()),
                List.of()
        );
    }

    public boolean knowsEventType(String eventType) {
        return knownEventTypes.contains(eventType);
    }
}
