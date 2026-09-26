package com.yumpoo.platform.identityaccess.infrastructure.event;

import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import com.yumpoo.platform.identityaccess.application.event.IdentityCommittedFactConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class IdentityOutboxRecoveryRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityOutboxRecoveryRunner.class);

    private final OutboxStorePort outboxStore;
    private final IdentityCommittedFactConsumer consumer;
    private final Clock clock;

    public IdentityOutboxRecoveryRunner(
            OutboxStorePort outboxStore,
            IdentityCommittedFactConsumer consumer,
            Clock clock
    ) {
        this.outboxStore = outboxStore;
        this.consumer = consumer;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant nextAttemptAt = clock.instant();
        int recovered = 0;
        for (EventSubscription subscription : consumer.subscriptions()) {
            recovered += outboxStore.requeueMissingConsumerEvents(subscription, nextAttemptAt);
        }
        if (recovered > 0) {
            LOGGER.info("identity outbox events requeued after consumer registration; count={}", recovered);
        }
    }
}
