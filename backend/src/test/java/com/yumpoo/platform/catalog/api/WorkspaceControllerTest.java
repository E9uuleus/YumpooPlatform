package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        controller = new WorkspaceController(actorProvider, service, new IfMatchParser());
    }

    @Test
    void detailReturnsStrongEtag() {
        when(service.findVisible(actor, WORKSPACE_ID)).thenReturn(view(3));
        assertThat(controller.detail(WORKSPACE_ID).getHeaders().getETag()).isEqualTo("\"3\"");
    }

    @Test
    void visibleResourceIsResolvedBeforeMissingIfMatchBecomes428() throws Exception {
        when(service.findForAdministration(actor, WORKSPACE_ID)).thenReturn(view(0));
        WorkspaceUpdateRequest body = new ObjectMapper().readValue(
                "{\"name\":\"主工作空间\",\"description\":null}", WorkspaceUpdateRequest.class);
        assertCode(() -> controller.update(WORKSPACE_ID, body, null), StandardErrorCode.PRECONDITION_REQUIRED);
    }

    @Test
    void controllerNoLongerPublishesCreationOrLifecycleMutations() {
        Set<String> methods = Arrays.stream(WorkspaceController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet());
        assertThat(methods).doesNotContain("create", "archive", "restore", "lifecycle");
    }

    private static WorkspaceView view(long version) {
        return new WorkspaceView(WORKSPACE_ID, "MAIN", "主工作空间", null,
                0, WorkspaceStatus.ACTIVE, 0, version);
    }

    private static void assertCode(Runnable action, StandardErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
