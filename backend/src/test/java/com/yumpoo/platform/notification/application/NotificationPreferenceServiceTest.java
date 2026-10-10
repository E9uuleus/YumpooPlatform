package com.yumpoo.platform.notification.application;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.notification.application.NotificationModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationPreferenceServiceTest {
    final UUID company=UUID.randomUUID(), user=UUID.randomUUID(), project=UUID.randomUUID();
    final CurrentActor actor=new CurrentActor(user,company,0,Set.of());
    final ProjectPreferenceUpdate update=new ProjectPreferenceUpdate(PreferenceMode.MUTED,false,false,false,false);
    NotificationRepository repository;
    NotificationContext context;
    NotificationPreferenceService service;

    @BeforeEach void setup() {
        repository=mock(NotificationRepository.class);
        context=mock(NotificationContext.class);
        service=new NotificationPreferenceService(repository,context);
        when(context.projectOwner(company,project)).thenReturn(Optional.of(user));
        when(context.eligibleProjectRecipients(company,project,Set.of(user))).thenReturn(Set.of(user));
        when(context.render(eq(actor),any())).thenAnswer(invocation->{
            NotificationContext.RenderRequest request=invocation.getArgument(1);
            UUID id=request.references().iterator().next().id();
            return new NotificationContext.Rendered(Map.of(id,
                    new Target(TargetKind.PROJECT,true,project,"可访问项目",null,null,null,null,null)),Map.of());
        });
    }

    @Test void availableMembersRetainDefaultsAndReadsAndWritesHoldTheProjectBeforeAuthorization() {
        assertThat(service.get(actor,project)).isEqualTo(ProjectPreference.defaults(project));
        var readOrder=inOrder(context,repository);
        readOrder.verify(context).projectOwner(company,project);
        readOrder.verify(context).eligibleProjectRecipients(company,project,Set.of(user));
        readOrder.verify(context).render(eq(actor),any());
        readOrder.verify(repository).preference(company,project,user);
        clearInvocations(context,repository);
        var saved=new ProjectPreference(project,PreferenceMode.MUTED,false,false,false,false,null);
        when(repository.savePreference(company,project,user,update)).thenReturn(saved);
        assertThat(service.update(actor,project,update)).isEqualTo(saved);
        var order=inOrder(context,repository);
        order.verify(context).projectOwner(company,project);
        order.verify(context).eligibleProjectRecipients(company,project,Set.of(user));
        order.verify(context).render(eq(actor),any());
        order.verify(repository).savePreference(company,project,user,update);
    }

    @Test void purgingOrDeletedProjectsCannotRecreatePreferences() {
        when(context.projectOwner(company,project)).thenReturn(Optional.empty());
        assertMissing(()->service.get(actor,project));
        assertMissing(()->service.update(actor,project,update));
        verifyNoInteractions(repository);
        verify(context,never()).eligibleProjectRecipients(any(),any(),any());
    }

    @Test void archivedProjectsAreHiddenFromOrdinaryMembersForReadsAndWrites() {
        when(context.render(eq(actor),any())).thenReturn(new NotificationContext.Rendered(Map.of(),Map.of()));
        assertMissing(()->service.get(actor,project));
        assertMissing(()->service.update(actor,project,update));
        verifyNoInteractions(repository);
    }

    @Test void effectiveAdministratorsStillRequireProjectMembershipForPersonalPreferences() {
        when(context.eligibleProjectRecipients(company,project,Set.of(user))).thenReturn(Set.of());
        assertMissing(()->service.get(actor,project));
        assertMissing(()->service.update(actor,project,update));
        verify(context,never()).render(any(),any());
        verifyNoInteractions(repository);
    }

    private void assertMissing(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND));
    }
}
