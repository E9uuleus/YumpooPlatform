package com.yumpoo.platform.notification.api;

import com.yumpoo.platform.foundation.application.event.*;
import com.yumpoo.platform.notification.application.NotificationRepository;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotificationInboxProjectionTest {
    final UUID company=UUID.randomUUID(),project=UUID.randomUUID(),item=UUID.randomUUID(),update=UUID.randomUUID();
    final UUID actor=UUID.randomUUID(),assignee=UUID.randomUUID(),creator=UUID.randomUUID(),parent=UUID.randomUUID();
    final Instant cutover=Instant.parse("2026-09-25T00:00:00Z");
    final ObjectMapper mapper=new ObjectMapper();
    NotificationRepository repository; NotificationContextPort context; NotificationInboxProjection projection;
    @BeforeEach void setup() {
        repository=mock(NotificationRepository.class);context=mock(NotificationContextPort.class);
        projection=new NotificationInboxProjection(repository,context);
        when(repository.acceptedFrom()).thenReturn(cutover);
        when(context.workItemParticipants(company,item)).thenReturn(Optional.of(new NotificationContextPort.Participants(project,assignee,creator)));
        when(context.update(company,update)).thenReturn(Optional.of(new NotificationContextPort.Update(project,item,actor,parent)));
        when(context.projectOwner(company,project)).thenReturn(Optional.of(creator));
        when(context.eligibleProjectRecipients(eq(company),eq(project),any())).thenAnswer(i->new HashSet<>((Collection<UUID>)i.getArgument(2)));
        when(context.activeAccounts(eq(company),any())).thenAnswer(i->new HashSet<>((Collection<UUID>)i.getArgument(1)));
    }
    @Test void commentsPrioritizeMentionThenReplyAndExcludeActor() {
        ObjectNode p=payload();p.putArray("mentionedUserIds").add(assignee.toString()).add(parent.toString()).add(actor.toString());
        projection.consume(event("workitem.work_item_update_published",2,p,cutover));
        verify(repository).append(any(),eq(Map.of(assignee,Reason.MENTION,parent,Reason.MENTION,creator,Reason.COMMENT)));
    }
    @Test void replyOverridesCommentAndEditOnlyNotifiesAddedMentions() {
        when(context.update(company,update)).thenReturn(Optional.of(new NotificationContextPort.Update(project,item,actor,assignee)));
        ObjectNode p=payload();p.putArray("mentionedUserIds");
        projection.consume(event("workitem.work_item_update_published",2,p,cutover));
        verify(repository).append(any(),eq(Map.of(assignee,Reason.REPLY,creator,Reason.COMMENT)));
        clearInvocations(repository);
        p.putArray("addedMentionedUserIds").add(parent.toString());
        projection.consume(event("workitem.work_item_update_edited",2,p,cutover));
        verify(repository).append(any(),eq(Map.of(parent,Reason.MENTION)));
    }
    @Test void createAndAssignmentNotifyAssigneeOnly() {
        ObjectNode p=payload();p.put("assigneeUserId",assignee.toString());
        projection.consume(event("workitem.work_item_created",2,p,cutover));
        projection.consume(event("workitem.work_item_assigned",1,p,cutover));
        verify(repository,times(2)).append(any(),eq(Map.of(assignee,Reason.ASSIGNED)));
    }
    @Test void projectMembershipAndTransferHaveDistinctRecipients() {
        ObjectNode p=payload();p.put("userId",assignee.toString());
        projection.consume(event("catalog.project_member_added",1,p,cutover));
        verify(repository).append(any(),eq(Map.of(assignee,Reason.PROJECT_MEMBER_ADDED,creator,Reason.PROJECT_MEMBER_ADDED)));
        when(context.eligibleProjectRecipients(eq(company),eq(project),any())).thenReturn(Set.of(creator));
        projection.consume(event("catalog.project_member_removed",1,p,cutover));
        verify(repository).append(any(),eq(Map.of(assignee,Reason.PROJECT_MEMBER_REMOVED,creator,Reason.PROJECT_MEMBER_REMOVED)));
        clearInvocations(repository);
        p.put("changeSource","OWNER_REASSIGNMENT");
        projection.consume(event("catalog.project_member_added",1,p,cutover));
        verify(repository,never()).append(any(),any());
        p.put("previousOwnerUserId",creator.toString());p.put("newOwnerUserId",assignee.toString());
        when(context.eligibleProjectRecipients(eq(company),eq(project),any())).thenReturn(Set.of(creator,assignee));
        projection.consume(event("catalog.project_owner_reassigned",1,p,cutover));
        verify(repository).append(any(),eq(Map.of(creator,Reason.PROJECT_OWNER_TRANSFERRED,assignee,Reason.PROJECT_OWNER_ASSIGNED)));
    }
    @Test void invalidMissingDeletedAndOldTargetsAreAcknowledgedButDatabaseFailureRetries() {
        assertThatCode(()->projection.consume(event("workitem.work_item_created",2,mapper.createObjectNode(),cutover))).doesNotThrowAnyException();
        projection.consume(event("workitem.work_item_created",2,payload(),cutover.minusSeconds(1)));
        when(context.workItemParticipants(company,item)).thenReturn(Optional.empty());
        assertThatCode(()->projection.consume(event("workitem.work_item_created",2,payload(),cutover))).doesNotThrowAnyException();
        verify(repository,never()).append(any(),any());
        when(context.workItemParticipants(company,item)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->projection.consume(event("workitem.work_item_created",2,payload(),cutover)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
    @Test void inactiveRecipientsAreRemovedAndOnlySupportedVersionsSubscribe() {
        when(context.eligibleProjectRecipients(eq(company),eq(project),any())).thenReturn(Set.of());
        ObjectNode p=payload();p.put("assigneeUserId",assignee.toString());
        projection.consume(event("workitem.work_item_assigned",1,p,cutover));
        verify(repository,never()).append(any(),any());
        assertThat(projection.subscriptions()).hasSize(7).contains(new EventSubscription("workitem.work_item_created",2))
                .doesNotContain(new EventSubscription("workitem.work_item_created",1));
    }
    ObjectNode payload() { return mapper.createObjectNode().put("projectId",project.toString()).put("workItemId",item.toString()).put("updateId",update.toString()); }
    DomainEventEnvelope event(String type,int version,ObjectNode p,Instant time) {
        return new DomainEventEnvelope(UUID.randomUUID(),type,version,time,"WorkItem",item,1,company,EventActor.user(actor),"inbox-test","inbox-test",null,p);
    }
}
