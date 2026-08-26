package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectFactWriteGuard;
import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;
import com.yumpoo.platform.catalog.api.ProjectAccessSnapshotQuery;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.PriorityLabel;
import static com.yumpoo.platform.workitem.application.WorkItemLabelModels.StatusLabel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemLabelServiceTest {
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private final WorkItemLabelRepository labels = mock(WorkItemLabelRepository.class);
    private final ProjectAccessSnapshotQuery access = mock(ProjectAccessSnapshotQuery.class);
    private final ProjectFactWriteGuard writeGuard = mock(ProjectFactWriteGuard.class);
    private final CurrentActor actor = new CurrentActor(UUID.randomUUID(), COMPANY_ID, 1, Set.of());
    private WorkItemLabelService service;

    @BeforeEach
    void setUp() {
        service = new WorkItemLabelService(labels, access, writeGuard,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(writeGuard.lockForFactWrite(actor, PROJECT_ID)).thenReturn(writable(
                ProjectFactWriteSnapshot.ActorProjectAccess.MEMBER));
        when(labels.version(COMPANY_ID, PROJECT_ID, true)).thenReturn(OptionalLong.of(4));
        when(labels.statuses(COMPANY_ID, PROJECT_ID)).thenReturn(List.of(
                new StatusLabel("NOT_STARTED", "未开始", "GRAY", "TODO", 10, true, true, false)));
        when(labels.priorities(COMPANY_ID, PROJECT_ID)).thenReturn(List.of(
                new PriorityLabel("MEDIUM", "中", "INDIGO", 10, true, false)));
    }

    @Test
    void createsProjectScopedLabelAndAdvancesCatalogVersion() {
        when(labels.insertPriority(eq(COMPANY_ID), eq(PROJECT_ID), any(), eq("客户紧急"),
                eq("RED"), eq(20), eq(NOW))).thenReturn(true);

        var result = service.createPriority(actor, PROJECT_ID, 4, " 客户紧急 ", "red");

        assertThat(result.rowVersion()).isEqualTo(5);
        assertThat(result.etag()).isEqualTo("\"5\"");
        verify(labels).incrementVersion(COMPANY_ID, PROJECT_ID, 4, NOW);
    }

    @Test
    void refusesToDeactivateOrDeleteProtectedNotStartedLabel() {
        assertThatThrownBy(() -> service.updateStatus(actor, PROJECT_ID, "NOT_STARTED", 4,
                null, null, false, null))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                    assertThat(exception.fieldViolations()).extracting(value -> value.code())
                            .containsExactly("PROTECTED_LABEL");
                });
        assertThatThrownBy(() -> service.deleteStatus(actor, PROJECT_ID, "NOT_STARTED", 4))
                .isInstanceOf(ApplicationException.class);
        verify(labels, never()).deleteStatus(any(), any(), any(), any());
    }

    @Test
    void refusesToDeleteLabelReferencedByExistingWorkItem() {
        when(labels.priorities(COMPANY_ID, PROJECT_ID)).thenReturn(List.of(
                new PriorityLabel("MEDIUM", "中", "INDIGO", 10, true, true)));

        assertThatThrownBy(() -> service.deletePriority(actor, PROJECT_ID, "MEDIUM", 4))
                .isInstanceOfSatisfying(ApplicationException.class, exception -> {
                    assertThat(exception.fieldViolations()).extracting(value -> value.message())
                            .containsExactly("你不能删除正在使用的标签");
                });
        verify(labels, never()).deletePriority(any(), any(), any(), any());
    }

    @Test
    void keepsCompanyAdministratorReadOnly() {
        when(writeGuard.lockForFactWrite(actor, PROJECT_ID)).thenReturn(writable(
                ProjectFactWriteSnapshot.ActorProjectAccess.COMPANY_ADMIN_READ_ONLY));

        assertThatThrownBy(() -> service.createStatus(actor, PROJECT_ID, 4, "待审核", "AMBER"))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    private static ProjectFactWriteSnapshot writable(
            ProjectFactWriteSnapshot.ActorProjectAccess actorAccess) {
        return new ProjectFactWriteSnapshot(PROJECT_ID, COMPANY_ID, "PRJ",
                ProjectFactWriteSnapshot.ProjectLifecycle.ACTIVE, actorAccess,
                "DEFAULT", 1);
    }
}
