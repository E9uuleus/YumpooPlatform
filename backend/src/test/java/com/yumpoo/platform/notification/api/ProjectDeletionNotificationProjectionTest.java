package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.event.DomainEventEnvelope;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventSubscription;
import com.yumpoo.platform.identityaccess.api.ActiveCompanyAdminQuery;
import com.yumpoo.platform.notification.application.NotificationModels.Reason;
import com.yumpoo.platform.notification.application.NotificationModels.TargetKind;
import com.yumpoo.platform.notification.application.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectDeletionNotificationProjectionTest {
    final UUID company=UUID.randomUUID(), project=UUID.randomUUID(), owner=UUID.randomUUID();
    final UUID administrator=UUID.randomUUID(), operator=UUID.randomUUID(), inactive=UUID.randomUUID();
    final Instant cutover=Instant.parse("2026-10-09T00:00:00Z"), deadline=cutover.plusSeconds(30L*86400);
    final ObjectMapper mapper=new ObjectMapper();
    NotificationRepository repository;
    NotificationContextPort context;
    ActiveCompanyAdminQuery admins;
    ProjectDeletionNotificationProjection projection;

    @BeforeEach void setup() {
        repository=mock(NotificationRepository.class);
        context=mock(NotificationContextPort.class);
        admins=mock(ActiveCompanyAdminQuery.class);
        projection=new ProjectDeletionNotificationProjection(repository,context,admins);
        when(repository.projectDeletionAcceptedFrom()).thenReturn(cutover);
        when(context.projectOwner(company,project)).thenReturn(Optional.of(owner));
        when(admins.findUserIds(company)).thenReturn(Set.of(administrator,operator,inactive));
        when(context.activeAccounts(eq(company),any())).thenAnswer(invocation->{
            var active=new HashSet<>((Collection<UUID>)invocation.getArgument(1));
            active.remove(inactive);
            return active;
        });
    }

    @Test void scheduleNotifiesOnlyCurrentOwnerAndActiveAdminsWithoutTheOperatorOrPreferenceFiltering() {
        var event=event("catalog.project_deletion_scheduled",1,scheduled(),cutover,EventActor.user(operator));
        projection.consume(event);
        var captured=ArgumentCaptor.forClass(NotificationRepository.Event.class);
        verify(repository).append(captured.capture(),eq(Map.of(owner,Reason.PROJECT_DELETION_SCHEDULED,
                administrator,Reason.PROJECT_DELETION_SCHEDULED)));
        var row=captured.getValue();
        assertThat(row.kind()).isEqualTo(TargetKind.PROJECT);
        assertThat(row.projectId()).isEqualTo(project);
        assertThat(row.sourceEventId()).isEqualTo(event.eventId());
        assertThat(row.actorUserId()).isEqualTo(operator);
        assertThat(row.deletionPurgeAfter()).isEqualTo(deadline);
        assertThat(row.workItemId()).isNull();
        assertThat(row.updateId()).isNull();
        assertThat(row.subjectUserId()).isNull();
        verify(repository,never()).preferences(any(),any());
        verify(repository,never()).preference(any(),any(),any());
        verify(context,never()).eligibleProjectRecipients(any(),any(),any());
    }

    @Test void cancellationExcludesTheCurrentOwnerWhenTheyOperateAndDoesNotReuseTheOldDeadline() {
        var payload=base().put("cancelledAt",cutover.toString()).put("cancelledBy",owner.toString());
        projection.consume(event("catalog.project_deletion_cancelled",1,payload,cutover,EventActor.user(owner)));
        var captured=ArgumentCaptor.forClass(NotificationRepository.Event.class);
        verify(repository).append(captured.capture(),eq(Map.of(administrator,Reason.PROJECT_DELETION_CANCELLED,
                operator,Reason.PROJECT_DELETION_CANCELLED)));
        assertThat(captured.getValue().deletionPurgeAfter()).isNull();
        assertThat(captured.getValue().actorUserId()).isEqualTo(owner);
    }

    @Test void reminderIncludesEveryActiveRecipientAndUsesItsEventDeadlineSnapshot() {
        when(admins.findUserIds(company)).thenReturn(Set.of(owner,administrator,operator));
        var payload=base().put("purgeAfter",deadline.toString()).put("remindedAt",deadline.minusSeconds(86400).toString());
        projection.consume(event("catalog.project_deletion_reminder_due",1,payload,deadline.minusSeconds(86400),
                EventActor.system("PROJECT_PURGER")));
        var captured=ArgumentCaptor.forClass(NotificationRepository.Event.class);
        verify(repository).append(captured.capture(),eq(Map.of(owner,Reason.PROJECT_DELETION_REMINDER,
                administrator,Reason.PROJECT_DELETION_REMINDER,operator,Reason.PROJECT_DELETION_REMINDER)));
        assertThat(captured.getValue().deletionPurgeAfter()).isEqualTo(deadline);
        assertThat(captured.getValue().actorUserId()).isNull();
        assertThat(projection.subscriptions()).containsExactlyInAnyOrder(
                new EventSubscription("catalog.project_deletion_scheduled",1),
                new EventSubscription("catalog.project_deletion_cancelled",1),
                new EventSubscription("catalog.project_deletion_reminder_due",1));
    }

    @Test void independentDeploymentWatermarkSkipsBacklogAndUnexpectedVersionsBeforeContextReads() {
        projection.consume(event("catalog.project_deletion_scheduled",1,scheduled(),cutover.minusNanos(1),EventActor.user(operator)));
        projection.consume(event("catalog.project_deletion_scheduled",2,scheduled(),cutover,EventActor.user(operator)));
        verifyNoInteractions(context,admins);
        verify(repository,never()).append(any(),any());
        verify(repository,never()).acceptedFrom();
        verify(repository,never()).connectionAcceptedFrom();
    }

    @Test void missingOrPurgingOwnerDoesNotRebuildDeletedNotificationsAndDatabaseFailureRetries() {
        var event=event("catalog.project_deletion_scheduled",1,scheduled(),cutover,EventActor.user(operator));
        when(context.projectOwner(company,project)).thenReturn(Optional.empty());
        projection.consume(event);
        verifyNoInteractions(admins);
        verify(repository,never()).append(any(),any());
        when(context.projectOwner(company,project)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->projection.consume(event)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void malformedPayloadActorOrAggregateIsAcknowledgedWithoutNotifications() {
        var payload=scheduled().put("purgeAfter",cutover.minusSeconds(1).toString());
        assertThatCode(()->projection.consume(event("catalog.project_deletion_scheduled",1,payload,cutover,EventActor.user(operator))))
                .doesNotThrowAnyException();
        projection.consume(event("catalog.project_deletion_scheduled",1,scheduled(),cutover,EventActor.user(owner)));
        projection.consume(event("catalog.project_deletion_scheduled",1,scheduled().put("projectId",UUID.randomUUID().toString()),
                cutover,EventActor.user(operator)));
        projection.consume(event("catalog.project_deletion_cancelled",1,base(),cutover,EventActor.user(operator)));
        verifyNoInteractions(context,admins);
        verify(repository,never()).append(any(),any());
    }

    private ObjectNode base() { return mapper.createObjectNode().put("projectId",project.toString()); }
    private ObjectNode scheduled() {
        return base().put("requestedAt",cutover.toString()).put("requestedBy",operator.toString()).put("purgeAfter",deadline.toString());
    }
    private DomainEventEnvelope event(String type,int version,ObjectNode payload,Instant occurredAt,EventActor actor) {
        return new DomainEventEnvelope(UUID.randomUUID(),type,version,occurredAt,"Project",project,1,company,actor,
                "project-deletion-test","project-deletion-test",null,payload);
    }
}
