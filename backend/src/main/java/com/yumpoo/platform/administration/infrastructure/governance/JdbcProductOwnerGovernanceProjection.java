package com.yumpoo.platform.administration.infrastructure.governance;

import com.yumpoo.platform.catalog.api.ProductOwnerScopeQuery;
import com.yumpoo.platform.catalog.api.ProductSnapshot;
import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JdbcProductOwnerGovernanceProjection implements OutboxEventConsumer {

    private static final Set<String> OWNER_BECAME_UNAVAILABLE = Set.of(
            "identity.user_employment_left",
            "identity.user_account_disabled");
    private static final Set<String> OWNER_MAY_BE_AVAILABLE = Set.of(
            "identity.user_employment_returned",
            "identity.user_account_enabled");
    private static final Set<String> PRODUCT_NO_LONGER_MISSING = Set.of(
            "catalog.product_owner_reassigned",
            "catalog.product_archived");

    private final JdbcClient jdbcClient;
    private final ProductOwnerScopeQuery productOwnerScopeQuery;
    private final ActiveUserSnapshotQuery activeUserSnapshotQuery;

    public JdbcProductOwnerGovernanceProjection(
            JdbcClient jdbcClient,
            ProductOwnerScopeQuery productOwnerScopeQuery,
            ActiveUserSnapshotQuery activeUserSnapshotQuery
    ) {
        this.jdbcClient = jdbcClient;
        this.productOwnerScopeQuery = productOwnerScopeQuery;
        this.activeUserSnapshotQuery = activeUserSnapshotQuery;
    }

    @Override
    public String consumerName() {
        return "administration-product-owner-governance-v1";
    }

    @Override
    public Set<EventSubscription> subscriptions() {
        return Set.of(
                new EventSubscription("identity.user_employment_left", 1),
                new EventSubscription("identity.user_employment_returned", 1),
                new EventSubscription("identity.user_account_disabled", 1),
                new EventSubscription("identity.user_account_enabled", 1),
                new EventSubscription("catalog.product_owner_reassigned", 1),
                new EventSubscription("catalog.product_archived", 1));
    }

    @Override
    public void consume(DomainEventEnvelope event) {
        if (OWNER_BECAME_UNAVAILABLE.contains(event.eventType())) {
            openForActiveProducts(event, event.aggregateId());
            return;
        }
        if (OWNER_MAY_BE_AVAILABLE.contains(event.eventType())) {
            resolveIfAvailable(event, event.aggregateId());
            return;
        }
        if (PRODUCT_NO_LONGER_MISSING.contains(event.eventType())) {
            resolveProduct(event, event.aggregateId(), "PRODUCT_OWNER_GOVERNED");
            return;
        }
        throw new IllegalArgumentException("unsupported product owner governance event");
    }

    private void openForActiveProducts(DomainEventEnvelope event, UUID ownerUserId) {
        for (ProductSnapshot product : productOwnerScopeQuery.findActiveByOwner(
                event.companyId(), ownerUserId)) {
            jdbcClient.sql("""
                            INSERT INTO yumpoo.governance_issue (
                                id, company_id, issue_type, target_type, target_id, status,
                                safe_summary_code, detected_event_id, detected_at,
                                created_at, updated_at
                            ) VALUES (
                                :id, :companyId, 'OWNER_MISSING', 'PRODUCT', :productId,
                                'OPEN', 'PRODUCT_OWNER_MISSING', :eventId, :occurredAt,
                                :occurredAt, :occurredAt
                            ) ON CONFLICT DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("companyId", event.companyId())
                    .param("productId", product.productId())
                    .param("eventId", event.eventId())
                    .param("occurredAt", utc(event))
                    .update();
        }
    }

    private void resolveIfAvailable(DomainEventEnvelope event, UUID ownerUserId) {
        ActiveUserSnapshot owner = activeUserSnapshotQuery.findByUserId(ownerUserId).orElse(null);
        if (owner == null || !owner.companyId().equals(event.companyId()) || !owner.activeAndEnabled()) {
            return;
        }
        List<ProductSnapshot> products = productOwnerScopeQuery.findActiveByOwner(
                event.companyId(), ownerUserId);
        for (ProductSnapshot product : products) {
            resolveProduct(event, product.productId(), "PRODUCT_OWNER_AVAILABLE");
        }
    }

    private void resolveProduct(DomainEventEnvelope event, UUID productId, String resolutionCode) {
        jdbcClient.sql("""
                        UPDATE yumpoo.governance_issue
                        SET status = 'RESOLVED',
                            resolved_event_id = :eventId,
                            resolved_at = :occurredAt,
                            resolution_code = :resolutionCode,
                            row_version = row_version + 1,
                            updated_at = :occurredAt
                        WHERE company_id = :companyId
                          AND issue_type = 'OWNER_MISSING'
                          AND target_type = 'PRODUCT'
                          AND target_id = :productId
                          AND status = 'OPEN'
                        """)
                .param("eventId", event.eventId())
                .param("occurredAt", utc(event))
                .param("resolutionCode", resolutionCode)
                .param("companyId", event.companyId())
                .param("productId", productId)
                .update();
    }

    private static OffsetDateTime utc(DomainEventEnvelope event) {
        return OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC);
    }
}
