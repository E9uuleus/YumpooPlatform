package com.yumpoo.platform.administration.infrastructure.governance;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class JdbcGovernanceIssueProjection implements OutboxEventConsumer {

    private static final String MISSING = "identity.app_manager_missing_detected";
    private static final String RESTORED = "identity.app_manager_availability_restored";
    private final JdbcClient jdbcClient;

    public JdbcGovernanceIssueProjection(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public String consumerName() {
        return "administration-governance-issue-v1";
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        return Set.of(
                new EventSubscription(MISSING, 1),
                new EventSubscription(RESTORED, 1)
        );
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        if (MISSING.equals(event.eventType())) {
            open(event);
        } else if (RESTORED.equals(event.eventType())) {
            resolve(event);
        } else {
            throw new IllegalArgumentException("unsupported governance event");
        }
    }

    private void open(DomainEventEnvelope event) {
        jdbcClient.sql("""
                        INSERT INTO yumpoo.governance_issue (
                            id, company_id, issue_type, target_type, target_id, status,
                            safe_summary_code, detected_event_id, detected_at,
                            created_at, updated_at
                        )
                        SELECT :id, :companyId, 'APP_MANAGER_MISSING', 'COMPANY', :companyId,
                               'OPEN', 'APP_MANAGER_MISSING', :eventId, :occurredAt,
                               :occurredAt, :occurredAt
                        WHERE NOT EXISTS (
                            SELECT 1 FROM yumpoo.governance_issue
                            WHERE company_id = :companyId
                              AND issue_type = 'APP_MANAGER_MISSING'
                              AND status = 'OPEN'
                        )
                        ON CONFLICT (detected_event_id, issue_type, target_type, target_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("companyId", event.companyId())
                .param("eventId", event.eventId())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
    }

    private void resolve(DomainEventEnvelope event) {
        jdbcClient.sql("""
                        UPDATE yumpoo.governance_issue
                        SET status = 'RESOLVED',
                            resolved_event_id = :eventId,
                            resolved_at = :occurredAt,
                            resolution_code = 'APP_MANAGER_AVAILABLE',
                            row_version = row_version + 1,
                            updated_at = :occurredAt
                        WHERE company_id = :companyId
                          AND issue_type = 'APP_MANAGER_MISSING'
                          AND status = 'OPEN'
                        """)
                .param("eventId", event.eventId())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .param("companyId", event.companyId())
                .update();
    }
}
