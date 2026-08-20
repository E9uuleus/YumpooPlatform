package com.yumpoo.platform.administration.infrastructure.governance;

import com.yumpoo.platform.catalog.api.ProjectOwnerScopeQuery;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Component
public class JdbcProjectOwnerGovernanceProjection implements OutboxEventConsumer {
    private static final Set<String> UNAVAILABLE = Set.of(
            "identity.user_employment_left", "identity.user_account_disabled");
    private static final Set<String> MAYBE_AVAILABLE = Set.of(
            "identity.user_employment_returned", "identity.user_account_enabled");

    private final JdbcClient jdbc;
    private final ProjectOwnerScopeQuery projects;
    private final ActiveUserSnapshotQuery users;

    public JdbcProjectOwnerGovernanceProjection(JdbcClient jdbc, ProjectOwnerScopeQuery projects,
                                                ActiveUserSnapshotQuery users) {
        this.jdbc=jdbc; this.projects=projects; this.users=users;
    }

    @Override public String consumerName() { return "administration-project-owner-governance-v1"; }

    @Override public Set<EventSubscription> subscriptions() {
        return Set.of(new EventSubscription("identity.user_employment_left",1),
                new EventSubscription("identity.user_employment_returned",1),
                new EventSubscription("identity.user_account_disabled",1),
                new EventSubscription("identity.user_account_enabled",1),
                new EventSubscription("catalog.project_owner_reassigned",1),
                new EventSubscription("catalog.project_created",1));
    }

    @Override public void consume(DomainEventEnvelope event) {
        if (UNAVAILABLE.contains(event.eventType())) {
            projects.findGovernedByOwner(event.companyId(),event.aggregateId())
                    .forEach(project -> open(event,project.projectId()));
            return;
        }
        if (MAYBE_AVAILABLE.contains(event.eventType())) {
            ActiveUserSnapshot user=users.findByUserId(event.aggregateId()).orElse(null);
            if (user!=null && user.companyId().equals(event.companyId()) && user.activeAndEnabled())
                projects.findGovernedByOwner(event.companyId(),event.aggregateId())
                        .forEach(project -> resolve(event,project.projectId(),"PROJECT_OWNER_AVAILABLE"));
            return;
        }
        ProjectSnapshot project=projects.find(event.companyId(),event.aggregateId()).orElse(null);
        if (project==null || "ARCHIVED".equals(project.lifecycle())) return;
        ActiveUserSnapshot owner=users.findByUserId(project.ownerUserId()).orElse(null);
        if (owner!=null && owner.companyId().equals(event.companyId()) && owner.activeAndEnabled())
            resolve(event,project.projectId(),"PROJECT_OWNER_GOVERNED");
        else open(event,project.projectId());
    }

    private void open(DomainEventEnvelope event, UUID projectId) {
        jdbc.sql("""
                INSERT INTO yumpoo.governance_issue (
                    id, company_id, issue_type, target_type, target_id, status,
                    safe_summary_code, detected_event_id, detected_at, created_at, updated_at
                ) VALUES (:id,:companyId,'OWNER_MISSING','PROJECT',:projectId,'OPEN',
                    'PROJECT_OWNER_MISSING',:eventId,:occurredAt,:occurredAt,:occurredAt)
                ON CONFLICT DO NOTHING
                """).param("id",UUID.randomUUID()).param("companyId",event.companyId())
                .param("projectId",projectId).param("eventId",event.eventId())
                .param("occurredAt",utc(event)).update();
    }

    private void resolve(DomainEventEnvelope event, UUID projectId, String code) {
        jdbc.sql("""
                UPDATE yumpoo.governance_issue SET status='RESOLVED', resolved_event_id=:eventId,
                    resolved_at=:occurredAt, resolution_code=:code, row_version=row_version+1,
                    updated_at=:occurredAt
                WHERE company_id=:companyId AND issue_type='OWNER_MISSING'
                  AND target_type='PROJECT' AND target_id=:projectId AND status='OPEN'
                """).param("eventId",event.eventId()).param("occurredAt",utc(event)).param("code",code)
                .param("companyId",event.companyId()).param("projectId",projectId).update();
    }

    private static OffsetDateTime utc(DomainEventEnvelope event) {
        return OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC);
    }
}
