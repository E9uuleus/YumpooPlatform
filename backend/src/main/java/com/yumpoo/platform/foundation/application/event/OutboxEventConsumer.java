package com.yumpoo.platform.foundation.application.event;

import java.util.Set;

/**
 * Outbox 的数据库事实消费者；实现应加入外层消费事务，不在此直接执行无幂等外部调用。
 */
public interface OutboxEventConsumer {

    String consumerName();

    Set<EventSubscription> subscriptions();

    void consume(DomainEventEnvelope event);
}
