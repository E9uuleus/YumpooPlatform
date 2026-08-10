package com.yumpoo.platform.foundation.application.outbox;

import java.util.UUID;

public record OutboxLease(UUID eventId, String leaseOwner, UUID leaseToken) {

    public OutboxLease {
        if (eventId == null || leaseToken == null) {
            throw new IllegalArgumentException("eventId and leaseToken must not be null");
        }
        if (leaseOwner == null || leaseOwner.isBlank() || leaseOwner.length() > 80) {
            throw new IllegalArgumentException("leaseOwner must be between 1 and 80 characters");
        }
    }
}
