package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.identityaccess.api.ActiveCompanyAdminQuery;
import com.yumpoo.platform.notification.application.NotificationRepository;
import com.yumpoo.platform.notification.application.NotificationModels.Reason;
import com.yumpoo.platform.notification.application.NotificationModels.TargetKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class ProjectDeletionNotificationProjection implements OutboxEventConsumer {
    private static final Logger LOG=LoggerFactory.getLogger(ProjectDeletionNotificationProjection.class);
    private final NotificationRepository repository;
    private final NotificationContextPort context;
    private final ActiveCompanyAdminQuery admins;
    public ProjectDeletionNotificationProjection(NotificationRepository repository, NotificationContextPort context,
            ActiveCompanyAdminQuery admins) { this.repository=repository; this.context=context; this.admins=admins; }
    public String consumerName() { return "notification-project-deletion-v1"; }
    public Set<EventSubscription> subscriptions() {
        return Set.of(new EventSubscription("catalog.project_deletion_scheduled",1),
                new EventSubscription("catalog.project_deletion_cancelled",1),
                new EventSubscription("catalog.project_deletion_reminder_due",1));
    }
    public void consume(DomainEventEnvelope event) {
        try {
            if (event.occurredAt().isBefore(repository.projectDeletionAcceptedFrom()) || event.eventVersion()!=1) return;
            JsonNode payload=event.payload();
            UUID project=UUID.fromString(payload.path("projectId").asText());
            if (!project.equals(event.aggregateId()) || !"Project".equals(event.aggregateType())) throw new IllegalArgumentException();
            Reason reason;
            Instant purgeAfter=null;
            UUID actor=null;
            switch (event.eventType()) {
                case "catalog.project_deletion_scheduled" -> {
                    reason=Reason.PROJECT_DELETION_SCHEDULED;
                    actor=UUID.fromString(payload.path("requestedBy").asText());
                    Instant requestedAt=Instant.parse(payload.path("requestedAt").asText());
                    purgeAfter=Instant.parse(payload.path("purgeAfter").asText());
                    if (!purgeAfter.isAfter(requestedAt)) throw new IllegalArgumentException();
                }
                case "catalog.project_deletion_cancelled" -> {
                    reason=Reason.PROJECT_DELETION_CANCELLED;
                    actor=UUID.fromString(payload.path("cancelledBy").asText());
                    Instant.parse(payload.path("cancelledAt").asText());
                }
                case "catalog.project_deletion_reminder_due" -> {
                    reason=Reason.PROJECT_DELETION_REMINDER;
                    purgeAfter=Instant.parse(payload.path("purgeAfter").asText());
                    Instant.parse(payload.path("remindedAt").asText());
                }
                default -> throw new IllegalArgumentException();
            }
            if (actor!=null && !actor.equals(event.actor().userId())) throw new IllegalArgumentException();
            var owner=context.projectOwner(event.companyId(),project);
            if (owner.isEmpty()) return;
            Set<UUID> recipients=new HashSet<>(admins.findUserIds(event.companyId()));
            recipients.add(owner.get());
            if (actor!=null) recipients.remove(actor);
            recipients.retainAll(context.activeAccounts(event.companyId(),recipients));
            if (recipients.isEmpty()) return;
            var reasons=new LinkedHashMap<UUID,Reason>();
            recipients.forEach(user->reasons.put(user,reason));
            repository.append(new NotificationRepository.Event(UUID.randomUUID(),event.companyId(),event.eventId(),
                    event.eventType(),event.eventVersion(),TargetKind.PROJECT,project,null,null,null,actor,
                    event.occurredAt(),purgeAfter),reasons);
        } catch (DataAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            LOG.atWarn().setMessage("project deletion notification source ignored")
                    .addKeyValue("eventId",event.eventId()).addKeyValue("exceptionType",failure.getClass().getSimpleName()).log();
        }
    }
}
