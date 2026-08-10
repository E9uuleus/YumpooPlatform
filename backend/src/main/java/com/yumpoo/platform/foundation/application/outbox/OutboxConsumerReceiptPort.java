package com.yumpoo.platform.foundation.application.outbox;

import java.time.Instant;
import java.util.UUID;

public interface OutboxConsumerReceiptPort {

    /**
     * 在当前消费事务内尝试创建回执。返回 false 表示其他事务已完成该消费者。
     */
    boolean tryBegin(String consumerName, UUID eventId, Instant completedAt);
}
