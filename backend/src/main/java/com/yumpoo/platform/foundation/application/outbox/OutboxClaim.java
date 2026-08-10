package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;

import java.time.Instant;

public record OutboxClaim(
        DomainEventEnvelope event,
        int attemptCount,
        OutboxLease lease,
        Instant leaseUntil
) {

    public OutboxClaim {
        if (event == null || lease == null || leaseUntil == null) {
            throw new IllegalArgumentException("claim fields must not be null");
        }
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        if (!event.eventId().equals(lease.eventId())) {
            throw new IllegalArgumentException("claim event and lease must refer to the same event");
        }
    }
}
