package com.yumpoo.platform.foundation.application.outbox;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxStorePort {

    void append(DomainEventEnvelope event);

    List<OutboxClaim> claimBatch(
            int batchSize,
            String leaseOwner,
            UUID leaseToken,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean markCompleted(OutboxLease lease, Instant completedAt);

    boolean markRetry(
            OutboxLease lease,
            OutboxFailure failure,
            Instant nextAttemptAt
    );

    boolean markDead(
            OutboxLease lease,
            OutboxFailure failure,
            Instant deadAt
    );

    /**
     * 显式恢复已补齐消费者的事件，仅重排因注册表无匹配消费者而 DEAD 的确切类型和版本。
     */
    int requeueMissingConsumerEvents(EventSubscription subscription, Instant nextAttemptAt);
}
