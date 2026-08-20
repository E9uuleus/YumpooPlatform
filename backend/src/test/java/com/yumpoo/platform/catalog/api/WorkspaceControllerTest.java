package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceLifecycleCommand;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("13000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("13000000-0000-4000-8000-000000000002");

    private WorkspaceService service;
    private WorkspaceController controller;
    private CurrentActor actor;

    @BeforeEach
    void setUp() {
        actor = new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
        CurrentActorProvider actorProvider = mock(CurrentActorProvider.class);
        when(actorProvider.requiredActive()).thenReturn(actor);
        service = mock(WorkspaceService.class);
        controller = new WorkspaceController(
                actorProvider, service, new IfMatchParser(), new IdempotencyKeyParser(),
                new IdempotencyRequestHasher(), new ObjectMapper());
    }

    @Test
    void detailReturnsStrongEtagAndCreateReturnsLocation() {
        when(service.findVisible(actor, WORKSPACE_ID)).thenReturn(view(3, WorkspaceStatus.ACTIVE));
        when(service.create(any())).thenReturn(IdempotencyExecutionResult.executed(
                new StoredCommandResult(201, "{\"code\":\"DELIVERY\"}", WORKSPACE_ID, "\"0\"")));

        ResponseEntity<?> detail = controller.detail(WORKSPACE_ID);
        ResponseEntity<String> created = controller.create(
                new WorkspaceCreateRequest("DELIVERY", "交付空间", null, 10),
                UUID.randomUUID().toString());

        assertThat(detail.getHeaders().getETag()).isEqualTo("\"3\"");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(created.getHeaders().getLocation())
                .hasToString("/api/v1/workspaces/" + WORKSPACE_ID);
    }

    @Test
    void visibleResourceIsResolvedBeforeMissingIfMatchBecomes428() throws Exception {
        when(service.findForAdministration(actor, WORKSPACE_ID))
                .thenReturn(view(0, WorkspaceStatus.ACTIVE));
        WorkspaceUpdateRequest body = new ObjectMapper().readValue(
                "{\"name\":\"交付空间\",\"description\":null,\"sortOrder\":10}",
                WorkspaceUpdateRequest.class);

        assertCode(() -> controller.update(WORKSPACE_ID, body, null),
                StandardErrorCode.PRECONDITION_REQUIRED);

        UUID hidden = UUID.fromString("13000000-0000-4000-8000-000000000404");
        when(service.findForAdministration(actor, hidden))
                .thenThrow(new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        assertCode(() -> controller.update(hidden, body, null), StandardErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void archiveParsesBothConditionalHeadersAndReturnsStoredEtag() {
        when(service.findForAdministration(actor, WORKSPACE_ID))
                .thenReturn(view(0, WorkspaceStatus.ACTIVE));
        when(service.archive(any(WorkspaceLifecycleCommand.class)))
                .thenReturn(IdempotencyExecutionResult.executed(new StoredCommandResult(
                        200, "{\"status\":\"ARCHIVED\"}", WORKSPACE_ID, "\"1\"")));

        ResponseEntity<String> response = controller.archive(
                WORKSPACE_ID, "\"0\"", UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"1\"");
        verify(service).archive(any(WorkspaceLifecycleCommand.class));
    }

    private static WorkspaceView view(long version, WorkspaceStatus status) {
        return new WorkspaceView(
                WORKSPACE_ID, "DELIVERY", "交付空间", null, 10, status, 0, version);
    }

    private static void assertCode(Runnable action, StandardErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
