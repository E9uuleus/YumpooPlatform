package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OutboxConsumerExecutor {

    private final OutboxConsumerReceiptPort receiptPort;
    private final Clock clock;

    public OutboxConsumerExecutor(OutboxConsumerReceiptPort receiptPort, Clock clock) {
        this.receiptPort = receiptPort;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxConsumerOutcome execute(
            OutboxEventConsumer consumer,
            DomainEventEnvelope event
    ) {
        boolean acquired = receiptPort.tryBegin(
                consumer.consumerName(),
                event.eventId(),
                clock.instant()
        );
        if (!acquired) {
            return OutboxConsumerOutcome.ALREADY_COMPLETED;
        }
        consumer.consume(event);
        return OutboxConsumerOutcome.CONSUMED;
    }
}
