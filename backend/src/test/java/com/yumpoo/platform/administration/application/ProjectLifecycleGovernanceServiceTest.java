package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.catalog.api.ProjectArchiveMutation;
import com.yumpoo.platform.catalog.api.ProjectLifecycleCommandPort;
import com.yumpoo.platform.catalog.api.ProjectRestoreSnapshot;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectLifecycleGovernanceServiceTest {
    private final UUID companyId = UUID.randomUUID(), projectId = UUID.randomUUID(), ownerId = UUID.randomUUID();
    private final ProjectLifecycleCommandPort projects = mock(ProjectLifecycleCommandPort.class);
    private final ProjectArchiveBlockerCollector blockers = mock(ProjectArchiveBlockerCollector.class);
    private final ActiveUserSnapshotQuery users = mock(ActiveUserSnapshotQuery.class);
    private final IdempotentCommandExecutor idempotency = mock(IdempotentCommandExecutor.class);
    private final SecurityAuditAppendPort audits = mock(SecurityAuditAppendPort.class);
    private final TransactionalEventPort events = mock(TransactionalEventPort.class);
    private ProjectLifecycleGovernanceService service;

    @BeforeEach
    void setUp() {
        when(idempotency.execute(any(), any())).thenAnswer(invocation ->
                IdempotencyExecutionResult.executed(invocation.<Supplier<StoredCommandResult>>getArgument(1).get()));
        service = new ProjectLifecycleGovernanceService(projects, blockers, users, idempotency, events, audits,
                new ObjectMapper(), Clock.systemUTC());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalArchiveDoesNotConsultBlockersForOwnerOrAdministrator(boolean admin) {
        CurrentActor actor = actor(admin ? UUID.randomUUID() : ownerId, admin);
        when(projects.lockForArchive(any())).thenReturn(snapshot("ACTIVE", 0));
        when(projects.archive(any())).thenReturn(snapshot("ARCHIVED", 1));
        var result = service.archive(new ProjectArchiveOperationCommand(actor, projectId, 0,
                UUID.randomUUID(), new RequestHash("a".repeat(64))));
        assertThat(result.result().responseJson()).contains("ARCHIVED");
        verify(projects).lockForArchive(new ProjectArchiveMutation(companyId, projectId, 0, actor.userId(), !admin));
        verifyNoInteractions(blockers);
        verify(audits).append(any());
        verify(events).append(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerAndAdministratorCanRestore(boolean admin) {
        when(projects.lockForRestore(any())).thenReturn(new ProjectRestoreSnapshot(snapshot("ARCHIVED", 1), true));
        when(projects.reopen(any())).thenReturn(snapshot("ACTIVE", 2));
        when(users.findByUserId(ownerId)).thenReturn(Optional.of(new ActiveUserSnapshot(ownerId, companyId, true, true, 0)));
        var result = service.restore(new ProjectRestoreOperationCommand(actor(admin ? UUID.randomUUID() : ownerId, admin),
                projectId, 1, UUID.randomUUID(), new RequestHash("b".repeat(64))));
        assertThat(result.result().responseJson()).contains("ACTIVE");
    }

    @Test
    void ordinaryMemberCannotRestoreAndNoMutationOrAuditIsWritten() {
        when(projects.lockForRestore(any())).thenReturn(new ProjectRestoreSnapshot(snapshot("ARCHIVED", 1), true));
        assertThatThrownBy(() -> service.restore(new ProjectRestoreOperationCommand(actor(UUID.randomUUID(), false),
                projectId, 1, UUID.randomUUID(), new RequestHash("c".repeat(64)))))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
        verify(projects, never()).reopen(any());
        verifyNoInteractions(audits, events, users);
    }

    private CurrentActor actor(UUID userId, boolean admin) {
        return new CurrentActor(userId, companyId, 0, admin ? Set.of(PlatformRoleCode.COMPANY_ADMIN) : Set.of());
    }
    private ProjectSnapshot snapshot(String lifecycle, long version) {
        return new ProjectSnapshot(projectId, companyId, UUID.randomUUID(), "P001", "归档项目", null, lifecycle, ownerId, version);
    }
}
