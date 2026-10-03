package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.foundation.application.event.OutboxEventConsumer;
import com.yumpoo.platform.notification.application.NotificationRepository;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class NotificationInboxProjection implements OutboxEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationInboxProjection.class);
    private static final String CONNECTION_CREATED = "workitem.connection_created";
    private final NotificationRepository repository;
    private final NotificationContextPort context;
    public NotificationInboxProjection(NotificationRepository repository, NotificationContextPort context) {
        this.repository = repository; this.context = context;
    }
    @Override public String consumerName() { return "notification-inbox-v1"; }
    @Override public Set<EventSubscription> subscriptions() {
        return Set.of(new EventSubscription("workitem.work_item_update_published",2),
                new EventSubscription("workitem.work_item_update_edited",2),
                new EventSubscription("workitem.work_item_created",2),
                new EventSubscription("workitem.work_item_assigned",1),
                new EventSubscription("catalog.project_member_added",1),
                new EventSubscription("catalog.project_member_removed",1),
                new EventSubscription("catalog.project_owner_reassigned",1),
                new EventSubscription(CONNECTION_CREATED,1));
    }
    @Override public void consume(DomainEventEnvelope event) {
        try {
            var acceptedFrom = CONNECTION_CREATED.equals(event.eventType())
                    ? repository.connectionAcceptedFrom() : repository.acceptedFrom();
            if (event.occurredAt().isBefore(acceptedFrom)) return;
            project(event);
        } catch (DataAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            LOG.atWarn().setMessage("inbox source event ignored").addKeyValue("event", "notification.source.ignored")
                    .addKeyValue("eventId", event.eventId())
                    .addKeyValue("exceptionType", failure.getClass().getSimpleName()).log();
        }
    }
    private void project(DomainEventEnvelope event) {
        if (CONNECTION_CREATED.equals(event.eventType())) {
            projectConnection(event);
            return;
        }
        JsonNode p = event.payload();
        UUID project = uuid(p,"projectId"), item = null, update = null, subject = null;
        TargetKind kind = TargetKind.PROJECT;
        Map<UUID,Reason> recipients = new LinkedHashMap<>();
        Set<UUID> membershipExempt = new HashSet<>();
        String type = event.eventType();
        if (type.startsWith("workitem.")) {
            item=uuid(p,"workItemId");
            var participants = context.workItemParticipants(event.companyId(),item).orElseThrow();
            if (!participants.projectId().equals(project)) throw new IllegalArgumentException();
            kind=TargetKind.WORK_ITEM;
            if (type.contains("update_")) {
                update=uuid(p,"updateId"); kind=TargetKind.WORK_ITEM_UPDATE;
                var comment=context.update(event.companyId(),update).orElseThrow();
                if (!comment.workItemId().equals(item) || !comment.projectId().equals(project)) throw new IllegalArgumentException();
                if (type.endsWith("published")) {
                    add(recipients,participants.assigneeUserId(),Reason.COMMENT);
                    add(recipients,participants.reporterUserId(),Reason.COMMENT);
                    add(recipients,comment.parentAuthorUserId(),Reason.REPLY);
                    mentions(p,"mentionedUserIds",recipients);
                } else if (type.endsWith("edited")) mentions(p,"addedMentionedUserIds",recipients);
                else throw new IllegalArgumentException();
            } else if (type.endsWith("created") || type.endsWith("assigned")) {
                add(recipients,optionalUuid(p,"assigneeUserId"),Reason.ASSIGNED);
            } else throw new IllegalArgumentException();
        } else {
            UUID owner=context.projectOwner(event.companyId(),project).orElseThrow();
            if (type.endsWith("member_added") || type.endsWith("member_removed")) {
                if (type.endsWith("member_added") && "OWNER_REASSIGNMENT".equals(p.path("changeSource").asText())) return;
                subject=uuid(p,"userId");
                Reason reason=type.endsWith("member_added")?Reason.PROJECT_MEMBER_ADDED:Reason.PROJECT_MEMBER_REMOVED;
                add(recipients,subject,reason); add(recipients,owner,reason);
                if (reason==Reason.PROJECT_MEMBER_REMOVED) membershipExempt.add(subject);
            } else if (type.endsWith("owner_reassigned")) {
                subject=uuid(p,"newOwnerUserId");
                add(recipients,uuid(p,"previousOwnerUserId"),Reason.PROJECT_OWNER_TRANSFERRED);
                add(recipients,subject,Reason.PROJECT_OWNER_ASSIGNED);
            } else throw new IllegalArgumentException();
        }
        recipients.remove(event.actor().userId());
        if (recipients.isEmpty()) return;
        Set<UUID> eligible = new HashSet<>(context.eligibleProjectRecipients(event.companyId(),project,recipients.keySet()));
        eligible.addAll(context.activeAccounts(event.companyId(),membershipExempt));
        recipients.keySet().retainAll(eligible);
        if (!recipients.isEmpty()) repository.append(new NotificationRepository.Event(UUID.randomUUID(),
                event.companyId(),event.eventId(),type,event.eventVersion(),kind,project,item,update,subject,
                event.actor().userId(),event.occurredAt()),recipients);
    }
    private void projectConnection(DomainEventEnvelope event) {
        JsonNode payload = event.payload();
        if (event.eventVersion()!=1 || !"CREATED".equals(payload.path("origin").asText())) return;
        UUID targetProject=uuid(payload,"targetProjectId"), targetItem=uuid(payload,"targetWorkItemId");
        var participants=context.workItemParticipants(event.companyId(),targetItem).orElseThrow();
        if (!targetProject.equals(participants.projectId())) throw new IllegalArgumentException();
        UUID owner=context.projectOwner(event.companyId(),targetProject).orElseThrow();
        if (owner.equals(event.actor().userId())
                || !context.eligibleProjectRecipients(event.companyId(),targetProject,Set.of(owner)).contains(owner)) return;
        repository.append(new NotificationRepository.Event(UUID.randomUUID(),event.companyId(),event.eventId(),
                event.eventType(),event.eventVersion(),TargetKind.WORK_ITEM,targetProject,targetItem,null,null,
                event.actor().userId(),event.occurredAt()),Map.of(owner,Reason.CONNECTION_CREATED));
    }
    private static void add(Map<UUID,Reason> recipients, UUID user, Reason reason) {
        if (user!=null) recipients.put(user,reason);
    }
    private static void mentions(JsonNode p,String field,Map<UUID,Reason> recipients) {
        JsonNode array=p.get(field);
        if (array==null || !array.isArray()) throw new IllegalArgumentException();
        for (JsonNode value:array) {
            if (!value.isTextual()) throw new IllegalArgumentException();
            add(recipients,UUID.fromString(value.asText()),Reason.MENTION);
        }
    }
    private static UUID optionalUuid(JsonNode p,String field) {
        return p.path(field).isMissingNode() || p.path(field).isNull()?null:uuid(p,field);
    }
    private static UUID uuid(JsonNode p,String field) {
        if (!p.path(field).isTextual()) throw new IllegalArgumentException();
        return UUID.fromString(p.path(field).asText());
    }
}
