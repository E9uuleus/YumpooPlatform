package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("12000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-20T04:00:00Z");

    private WorkspaceRepository repository;
    private com.yumpoo.platform.catalog.application.project.ProjectRepository projectRepository;
    private IdempotentCommandExecutor executor;
    private TransactionalEventPort eventPort;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkspaceRepository.class);
        projectRepository = mock(com.yumpoo.platform.catalog.application.project.ProjectRepository.class);
        when(projectRepository.countVisibleCurrentByWorkspace(any(), any())).thenReturn(java.util.Map.of());
        executor = mock(IdempotentCommandExecutor.class);
        eventPort = mock(TransactionalEventPort.class);
        service = new WorkspaceService(
                repository, projectRepository, executor, eventPort, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createProducesReplayableResponseAndDescriptionFreeEvent() {
        when(repository.insert(any())).thenReturn(true);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResult> callback = invocation.getArgument(1);
            return IdempotencyExecutionResult.executed(callback.get());
        });

        IdempotencyExecutionResult result = service.create(new WorkspaceCreateCommand(
                admin(), "DELIVERY", "交付空间", "不应进入事件的描述", 10,
                UUID.randomUUID(), requestHash()));

        assertThat(result.result().httpStatus()).isEqualTo(201);
        assertThat(result.result().etag()).isEqualTo("\"0\"");
        assertThat(result.result().responseJson()).contains("DELIVERY", "visibleProjectCount");

        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(eventPort).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("catalog.workspace_created");
        assertThat(event.getValue().payload().get("code").stringValue()).isEqualTo("DELIVERY");
        assertThat(event.getValue().payload().has("description")).isFalse();
    }

    @Test
    void duplicateCodeIsAFieldValidationFailure() {
        when(repository.insert(any())).thenReturn(false);
        executeCallbacks();

        assertThatThrownBy(() -> service.create(new WorkspaceCreateCommand(
                admin(), "DELIVERY", "交付空间", null, 10, UUID.randomUUID(), requestHash())))
                .isInstanceOfSatisfying(ApplicationException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(StandardErrorCode.VALIDATION_FAILED);
                    assertThat(error.fieldViolations()).singleElement().satisfies(violation -> {
                        assertThat(violation.field()).isEqualTo("code");
                        assertThat(violation.code()).isEqualTo("ALREADY_EXISTS");
                    });
                });
    }

    @Test
    void noChangePatchKeepsVersionAndDoesNotPersistOrPublishEvent() {
        Workspace workspace = workspace(WorkspaceStatus.ACTIVE, 7);
        when(repository.findById(COMPANY_ID, WORKSPACE_ID)).thenReturn(Optional.of(workspace));

        WorkspaceView result = service.update(new WorkspaceUpdateCommand(
                admin(), WORKSPACE_ID, 7, " 交付空间 ", "  ", 10));

        assertThat(result.rowVersion()).isEqualTo(7);
        verify(repository, never()).updateDetails(any(), any(Long.class));
        verify(eventPort, never()).append(any());
    }

    @Test
    void stalePatchIsRejectedBeforeWriting() {
        when(repository.findById(COMPANY_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(workspace(WorkspaceStatus.ACTIVE, 3)));

        assertError(StandardErrorCode.VERSION_CONFLICT, () -> service.update(new WorkspaceUpdateCommand(
                admin(), WORKSPACE_ID, 2, "新名称", null, 10)));
        verify(repository, never()).updateDetails(any(), any(Long.class));
    }

    @Test
    void memberCanReadActiveButArchivedResourceIsHidden() {
        when(repository.findById(COMPANY_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(workspace(WorkspaceStatus.ARCHIVED, 1)));

        assertError(StandardErrorCode.RESOURCE_NOT_FOUND, () -> service.findVisible(member(), WORKSPACE_ID));
        assertError(StandardErrorCode.ACCESS_DENIED,
                () -> service.findAll(member(), WorkspaceListStatus.ARCHIVED));
        verify(repository, never()).findAll(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void archiveChecksLifecycleAndPublishesTransition() {
        Workspace before = workspace(WorkspaceStatus.ACTIVE, 0);
        Workspace after = before.changeStatus(WorkspaceStatus.ARCHIVED, ACTOR_ID, NOW);
        when(repository.findById(COMPANY_ID, WORKSPACE_ID)).thenReturn(Optional.of(before));
        when(repository.changeStatus(any(), any(), any(Long.class))).thenReturn(Optional.of(after));
        executeCallbacks();

        IdempotencyExecutionResult result = service.archive(new WorkspaceLifecycleCommand(
                admin(), WORKSPACE_ID, 0, UUID.randomUUID(), requestHash()));

        assertThat(result.result().etag()).isEqualTo("\"1\"");
        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(eventPort).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("catalog.workspace_archived");
        assertThat(event.getValue().payload().get("fromStatus").stringValue()).isEqualTo("ACTIVE");
        assertThat(event.getValue().payload().get("toStatus").stringValue()).isEqualTo("ARCHIVED");
    }

    @Test
    void onlyCompanyAdminCanMutate() {
        assertError(StandardErrorCode.ACCESS_DENIED, () -> service.create(new WorkspaceCreateCommand(
                member(), "DELIVERY", "交付空间", null, 0, UUID.randomUUID(), requestHash())));
        verify(executor, never()).execute(any(), any());
    }

    @SuppressWarnings("unchecked")
    private void executeCallbacks() {
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResult> callback = invocation.getArgument(1);
            return IdempotencyExecutionResult.executed(callback.get());
        });
    }

    private static Workspace workspace(WorkspaceStatus status, long version) {
        return new Workspace(
                WORKSPACE_ID, COMPANY_ID, "DELIVERY", "交付空间", null, 10, status, version,
                NOW.minusSeconds(10), ACTOR_ID, NOW, ACTOR_ID);
    }

    private static CurrentActor admin() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor member() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());
    }

    private static RequestHash requestHash() {
        return new RequestHash("a".repeat(64));
    }

    private static void assertError(StandardErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
