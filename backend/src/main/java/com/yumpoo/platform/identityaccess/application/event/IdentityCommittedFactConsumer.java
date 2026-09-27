package com.yumpoo.platform.identityaccess.application.event;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class IdentityCommittedFactConsumer implements OutboxEventConsumer {

    private static final Set<EventSubscription> SUBSCRIPTIONS = Set.of(
            new EventSubscription("identity.directory_sync_started", 1),
            new EventSubscription("identity.directory_sync_completed", 1),
            new EventSubscription("identity.directory_sync_completed", 2),
            new EventSubscription("identity.directory_sync_failed", 1),
            new EventSubscription("identity.login_succeeded", 1),
            new EventSubscription("identity.login_rejected", 1),
            new EventSubscription("identity.user_sessions_revoked", 1),
            new EventSubscription("identity.user_sessions_revoked", 2),
            new EventSubscription("identity.platform_role_granted", 1),
            new EventSubscription("identity.platform_role_revoked", 1)
    );

    @Override
    public String consumerName() {
        return "identity-committed-facts-v1";
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        return SUBSCRIPTIONS;
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        // 业务变更及所需安全审计已由写事务完成，只需外层 executor 保存幂等回执。
        // 就业、账号状态和管理员可用性事件仍由各自治理投影处理，不在此确认。
    }
}
