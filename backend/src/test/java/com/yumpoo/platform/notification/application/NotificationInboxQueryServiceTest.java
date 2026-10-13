package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class NotificationInboxQueryServiceTest {
    @Test void memberDayDetailsRequireAnAdministratorAndDoNotReachBusinessReferenceRendering() {
        UUID company=UUID.randomUUID(), user=UUID.randomUUID(), subject=UUID.randomUUID(), id=UUID.randomUUID();
        Instant now=Instant.parse("2026-10-12T00:00:00Z");
        LocalDate day=LocalDate.parse("2026-10-11");
        var admin=new CurrentActor(user,company,0,Set.of(PlatformRoleCode.COMPANY_ADMIN));
        var member=new CurrentActor(user,company,0,Set.of());
        var repository=mock(NotificationRepository.class);
        var context=mock(NotificationContext.class);
        var service=new NotificationInboxQueryService(repository,context,new NotificationCursorCodec());
        var event=new NotificationRepository.Event(UUID.randomUUID(),company,UUID.randomUUID(),"reporting.work_hours_anomaly_detected",1,
                TargetKind.MEMBER_DAY,null,null,null,subject,null,now,null,new NotificationRepository.WorkHours(day,39_600_000L,600));
        var row=new NotificationRepository.Row(id,Reason.WORK_HOURS_ANOMALY,State.UNREAD,now,null,event);
        when(repository.serverNow()).thenReturn(now);
        when(repository.find(eq(company),eq(user),eq(ListState.ALL),isNull(),isNull(),eq(21))).thenReturn(List.of(row));
        when(context.render(any(),any())).thenReturn(new NotificationContext.Rendered(Map.of(),Map.of(subject,new Person(subject,"李四"))));

        var visible=service.list(admin,null,null,null,null).items().getFirst();
        assertThat(visible.target().accessible()).isTrue();
        assertThat(visible.target().workDate()).isEqualTo(day);
        assertThat(visible.target().durationMs()).isEqualTo(39_600_000L);
        assertThat(visible.target().thresholdMinutes()).isEqualTo(600);
        var hidden=service.list(member,null,null,null,null).items().getFirst();
        assertThat(hidden.target()).isEqualTo(Target.inaccessible(TargetKind.MEMBER_DAY));
        ArgumentCaptor<NotificationContext.RenderRequest> request=ArgumentCaptor.forClass(NotificationContext.RenderRequest.class);
        verify(context,times(2)).render(any(),request.capture());
        assertThat(request.getAllValues()).allSatisfy(r->{
            assertThat(r.references()).isEmpty();
            assertThat(r.userIds()).contains(subject);
        });
    }
    @Test void historicalDeletionDeadlineUsesTheEventSnapshotAndDisappearsWhenAccessIsRevoked() {
        UUID company=UUID.randomUUID(), user=UUID.randomUUID(), project=UUID.randomUUID(), id=UUID.randomUUID();
        Instant now=Instant.parse("2026-10-10T00:00:00Z"), deadline=now.plusSeconds(29L*86400);
        var actor=new CurrentActor(user,company,0,Set.of());
        var repository=mock(NotificationRepository.class);
        var context=mock(NotificationContext.class);
        var service=new NotificationInboxQueryService(repository,context,new NotificationCursorCodec());
        var event=new NotificationRepository.Event(UUID.randomUUID(),company,UUID.randomUUID(),
                "catalog.project_deletion_scheduled",1,TargetKind.PROJECT,project,null,null,null,user,now,deadline);
        var row=new NotificationRepository.Row(id,Reason.PROJECT_DELETION_SCHEDULED,State.UNREAD,now,null,event);
        when(repository.serverNow()).thenReturn(now);
        when(repository.find(eq(company),eq(user),eq(ListState.ALL),eq(Group.PROJECT),isNull(),eq(21))).thenReturn(List.of(row));
        when(context.render(eq(actor),any())).thenReturn(new NotificationContext.Rendered(
                Map.of(id,new Target(TargetKind.PROJECT,true,project,"当前项目名",null,null,null,null,null)),Map.of()));
        var visible=service.list(actor,null,Group.PROJECT,null,null).items().getFirst();
        assertThat(visible.deletionPurgeAfter()).isEqualTo(deadline);
        assertThat(visible.target().projectName()).isEqualTo("当前项目名");
        when(context.render(eq(actor),any())).thenReturn(new NotificationContext.Rendered(Map.of(),Map.of()));
        var hidden=service.list(actor,null,Group.PROJECT,null,null).items().getFirst();
        assertThat(hidden.target().accessible()).isFalse();
        assertThat(hidden.target().projectId()).isNull();
        assertThat(hidden.deletionPurgeAfter()).isNull();
    }
}
