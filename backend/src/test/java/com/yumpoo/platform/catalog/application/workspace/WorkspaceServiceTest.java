package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.application.project.ProjectRepository;
import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID WORKSPACE_ID = UUID.fromString("12000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-23T04:00:00Z");

    private WorkspaceRepository repository;
    private ProjectRepository projects;
    private TransactionalEventPort events;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkspaceRepository.class);
        projects = mock(ProjectRepository.class);
        events = mock(TransactionalEventPort.class);
        when(projects.countVisibleCurrentByWorkspace(any(), any())).thenReturn(Map.of());
        service = new WorkspaceService(repository, projects, events, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void activeActorAlwaysSeesTheSingletonMainWorkspace() {
        Workspace main = mainWorkspace();
        when(repository.findAll(COMPANY_ID, WorkspaceListStatus.ACTIVE)).thenReturn(java.util.List.of(main));

        assertThat(service.findAll(member(), WorkspaceListStatus.ACTIVE))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.code()).isEqualTo("MAIN");
                    assertThat(view.status()).isEqualTo(WorkspaceStatus.ACTIVE);
                    assertThat(view.sortOrder()).isZero();
                });
    }

    @Test
    void administratorCanPatchOnlyNameAndDescriptionWithStrongVersion() {
        Workspace before = mainWorkspace();
        Workspace after = before.updateDetails("研发主空间", "统一项目归属", ACTOR_ID, NOW);
        when(repository.findById(COMPANY_ID, WORKSPACE_ID)).thenReturn(Optional.of(before));
        when(repository.updateDetails(any(), eq(0L))).thenReturn(Optional.of(after));

        WorkspaceView result = service.update(new WorkspaceUpdateCommand(
                admin(), WORKSPACE_ID, 0, " 研发主空间 ", "统一项目归属"));

        assertThat(result.code()).isEqualTo("MAIN");
        assertThat(result.sortOrder()).isZero();
        assertThat(result.status()).isEqualTo(WorkspaceStatus.ACTIVE);
        assertThat(result.rowVersion()).isOne();
        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(events).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("catalog.workspace_updated");
        assertThat(event.getValue().payload().has("description")).isFalse();
    }

    @Test
    void unchangedPatchDoesNotWriteOrPublish() {
        Workspace before = mainWorkspace();
        when(repository.findById(COMPANY_ID, WORKSPACE_ID)).thenReturn(Optional.of(before));

        WorkspaceView result = service.update(new WorkspaceUpdateCommand(
                admin(), WORKSPACE_ID, 0, " 主工作空间 ", " "));

        assertThat(result.rowVersion()).isZero();
        verify(repository, never()).updateDetails(any(), any(Long.class));
        verify(events, never()).append(any());
    }

    @Test
    void memberCannotPatchMainWorkspace() {
        assertThatThrownBy(() -> service.update(new WorkspaceUpdateCommand(
                member(), WORKSPACE_ID, 0, "无权限", null)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }

    private static Workspace mainWorkspace() {
        return new Workspace(WORKSPACE_ID, COMPANY_ID, "MAIN", "主工作空间", null, 0,
                WorkspaceStatus.ACTIVE, 0, NOW.minusSeconds(60), null, NOW.minusSeconds(60), null);
    }

    private static CurrentActor admin() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static CurrentActor member() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());
    }
}
