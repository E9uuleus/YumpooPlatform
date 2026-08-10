package com.yumpoo.platform.foundation.application.event;

import com.yumpoo.platform.foundation.application.outbox.OutboxStorePort;
import com.yumpoo.platform.foundation.application.request.RequestCorrelation;
import com.yumpoo.platform.foundation.application.request.RequestCorrelationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionalEventPublisher implements TransactionalEventPort {

    private final OutboxStorePort storePort;
    private final Clock clock;

    public TransactionalEventPublisher(OutboxStorePort storePort, Clock clock) {
        this.storePort = storePort;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEventEnvelope append(EventDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
        RequestCorrelation correlation = RequestCorrelationContext.required();
        Instant occurredAt = clock.instant();
        DomainEventEnvelope event = new DomainEventEnvelope(
                UUID.randomUUID(),
                draft.eventType(),
                draft.eventVersion(),
                occurredAt,
                draft.aggregateType(),
                draft.aggregateId(),
                draft.aggregateVersion(),
                draft.companyId(),
                draft.actor(),
                correlation.requestId(),
                correlation.correlationId(),
                correlation.causationId(),
                draft.payload()
        );
        storePort.append(event);
        return event;
    }
}
