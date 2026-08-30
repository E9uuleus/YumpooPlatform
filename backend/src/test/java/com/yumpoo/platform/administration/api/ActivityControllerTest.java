package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.audit.api.ActivityPage;
import com.yumpoo.platform.audit.api.ActivityQueryPort;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.workitem.api.WorkItemActivitySourceQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityControllerTest {
    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();
    private static final Instant CUTOVER = Instant.parse("2026-08-30T08:00:00Z");

    @Test
    void companyAdminCanReadArchivedProjectWithoutWriteCapability() {
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), COMPANY, 1,
                Set.of(PlatformRoleCode.COMPANY_ADMIN));
        Fixture fixture = fixture(actor);
        when(fixture.access.findVisible(actor, PROJECT)).thenReturn(Optional.of(
                project(ProjectAccessSnapshot.ProjectLifecycle.ARCHIVED,
                        ProjectAccessSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY,
                        OptionalLong.empty())));
        when(fixture.activity.findProject(eq(COMPANY), eq(PROJECT), any()))
                .thenReturn(new ActivityPage(List.of(), null, CUTOVER));

        ActivityPage page = fixture.controller.project(PROJECT, null, null, null, null,
                null, null).getBody();
        assertThat(page).isNotNull();
        assertThat(page.historyStartedAt()).isEqualTo(CUTOVER);
    }

    @Test
    void appManagerWithoutProjectMembershipIsHiddenAsNotFound() {
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), COMPANY, 1,
                Set.of(PlatformRoleCode.APP_MANAGER));
        Fixture fixture = fixture(actor);
        when(fixture.access.findVisible(actor, PROJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.controller.project(PROJECT, null, null,
                null, null, null, null)).isInstanceOf(ApplicationException.class)
                .extracting(failure -> ((ApplicationException) failure).errorCode())
                .isEqualTo(StandardErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void softDeletedWorkItemUsesIncludingDeletedLocatorThenCurrentProjectAcl() {
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), COMPANY, 1, Set.of());
        Fixture fixture = fixture(actor);
        when(fixture.workItems.findIncludingDeleted(COMPANY, ITEM)).thenReturn(Optional.of(
                new WorkItemActivitySourceQuery.WorkItemActivityReference(ITEM, PROJECT,
                        UUID.randomUUID(), "YMP-20", "已删除事项")));
        when(fixture.access.findVisible(actor, PROJECT)).thenReturn(Optional.of(project(
                ProjectAccessSnapshot.ProjectLifecycle.ACTIVE,
                ProjectAccessSnapshot.ActorProjectAccess.MEMBER, OptionalLong.of(3))));
        when(fixture.activity.findWorkItem(eq(COMPANY), eq(PROJECT), eq(ITEM), any()))
                .thenReturn(new ActivityPage(List.of(), null, CUTOVER));

        fixture.controller.workItem(ITEM, null, null, null, null, null, null);
        verify(fixture.activity).findWorkItem(eq(COMPANY), eq(PROJECT), eq(ITEM), any());
    }

    private static Fixture fixture(CurrentActor actor) {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        ProjectAccessSnapshotQuery access = mock(ProjectAccessSnapshotQuery.class);
        WorkItemActivitySourceQuery workItems = mock(WorkItemActivitySourceQuery.class);
        ActivityQueryPort activity = mock(ActivityQueryPort.class);
        when(actors.requiredActive()).thenReturn(actor);
        return new Fixture(new ActivityController(actors, access, workItems, activity),
                access, workItems, activity);
    }

    private static ProjectAccessSnapshot project(ProjectAccessSnapshot.ProjectLifecycle lifecycle,
            ProjectAccessSnapshot.ActorProjectAccess access, OptionalLong membershipVersion) {
        return new ProjectAccessSnapshot(PROJECT, COMPANY, lifecycle, access,
                "DEFAULT", 1, 2, membershipVersion);
    }

    private record Fixture(ActivityController controller, ProjectAccessSnapshotQuery access,
            WorkItemActivitySourceQuery workItems, ActivityQueryPort activity) {
    }
}
