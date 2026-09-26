package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.catalog.api.*;
import com.yumpoo.platform.identityaccess.api.*;
import com.yumpoo.platform.notification.api.NotificationContextPort.*;
import com.yumpoo.platform.notification.api.NotificationModels.TargetKind;
import com.yumpoo.platform.workitem.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationContextAdapterTest {
    @Test void rendersBatchOnceAndHidesEveryReferenceWhenProjectAccessIsLost() {
        var users=mock(MinimalUserSnapshotQuery.class);
        var members=mock(ProjectActiveMembershipQuery.class);
        var access=mock(ProjectAccessSnapshotQuery.class);
        var projects=mock(ProjectOwnerScopeQuery.class);
        var source=mock(WorkItemNotificationSourceQuery.class);
        var items=mock(WorkItemReferenceQuery.class);
        var adapter=new NotificationContextAdapter(users,members,access,projects,source,items);
        UUID company=UUID.randomUUID(), project=UUID.randomUUID(), item=UUID.randomUUID(), user=UUID.randomUUID();
        var actor=new CurrentActor(user,company,0,Set.of());
        List<Reference> references=new ArrayList<>();
        for(int i=0;i<50;i++) references.add(new Reference(UUID.randomUUID(),TargetKind.WORK_ITEM,project,item,null));
        when(access.findVisible(eq(actor),anyCollection())).thenReturn(Map.of());
        var result=adapter.render(actor,new RenderRequest(references,Set.of(user)));
        assertThat(result.targets()).hasSize(50);
        assertThat(result.targets().values()).allSatisfy(target->{
            assertThat(target.accessible()).isFalse();assertThat(target.projectId()).isNull();
            assertThat(target.workItemId()).isNull();assertThat(target.title()).isNull();assertThat(target.excerpt()).isNull();
        });
        verify(access).findVisible(eq(actor),anyCollection());
        verify(projects).findAll(company,Set.of());
        verify(items).findVisible(actor,Set.of());
        verify(source).findVisibleUpdateExcerpts(actor,Set.of());
        verify(users).findByUserIds(company,Set.of(user));
    }
    @Test void recipientEligibilityRequiresMembershipAndActiveEnabledAccount() {
        var users=mock(MinimalUserSnapshotQuery.class);var members=mock(ProjectActiveMembershipQuery.class);
        var adapter=new NotificationContextAdapter(users,members,mock(ProjectAccessSnapshotQuery.class),mock(ProjectOwnerScopeQuery.class),
                mock(WorkItemNotificationSourceQuery.class),mock(WorkItemReferenceQuery.class));
        UUID company=UUID.randomUUID(),project=UUID.randomUUID(),active=UUID.randomUUID(),disabled=UUID.randomUUID();
        when(members.findActiveMemberIds(company,project,Set.of(active,disabled))).thenReturn(Set.of(active,disabled));
        when(users.findByUserIds(company,Set.of(active,disabled))).thenReturn(Map.of(
                active,new MinimalUserSnapshot(active,company,"有效","ACTIVE","ENABLED"),
                disabled,new MinimalUserSnapshot(disabled,company,"停用","ACTIVE","DISABLED")));
        assertThat(adapter.eligibleProjectRecipients(company,project,Set.of(active,disabled))).containsExactly(active);
    }
}
