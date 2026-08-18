package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceCommand;
import com.yumpoo.platform.administration.application.ProjectTemplateGovernanceService;
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
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectTemplateControllerTest {

    private static final UUID ACTOR_ID = UUID.fromString("16000000-0000-4000-8000-000000000011");
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TEMPLATE_ID = UUID.fromString("16000000-0000-4000-8000-000000000012");

    private ProjectTemplateGovernanceService service;
    private ProjectTemplateController controller;
    private CurrentActor actor;

    @BeforeEach
    void setUp() {
        actor = new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
        CurrentActorProvider actorProvider = mock(CurrentActorProvider.class);
        when(actorProvider.requiredActive()).thenReturn(actor);
        service = mock(ProjectTemplateGovernanceService.class);
        controller = new ProjectTemplateController(
                actorProvider, service, new IfMatchParser(), new IdempotencyKeyParser(),
                new IdempotencyRequestHasher(), new ObjectMapper());
    }

    @Test
    void detailAndMutationReturnLatestStrongEtagAndStoredBody() {
        ProjectTemplateSnapshot draft = snapshot("DRAFT", 0);
        when(service.findAnyForAdministration(actor, "RND", 2)).thenReturn(draft);
        StoredCommandResult stored = new StoredCommandResult(
                200, "{\"lifecycleStatus\":\"PUBLISHED\"}", TEMPLATE_ID, "\"1\"");
        when(service.publish(any(ProjectTemplateGovernanceCommand.class)))
                .thenReturn(IdempotencyExecutionResult.executed(stored));

        ResponseEntity<ProjectTemplateSnapshot> detail = controller.detail("RND", 2);
        ResponseEntity<String> published = controller.publish(
                "RND", 2, new ProjectTemplateReasonRequest("  发布 V2  "), "\"0\"",
                UUID.randomUUID().toString());

        assertThat(detail.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        assertThat(published.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(published.getBody()).contains("PUBLISHED");
        verify(service).publish(any(ProjectTemplateGovernanceCommand.class));
    }

    @Test
    void visibleResourceIsResolvedBeforeIfMatchAndMissingHeaderBecomes428() {
        when(service.findAnyForAdministration(actor, "RND", 2)).thenReturn(snapshot("DRAFT", 0));

        assertCode(() -> controller.publish(
                        "RND", 2, new ProjectTemplateReasonRequest("发布"), null,
                        UUID.randomUUID().toString()),
                StandardErrorCode.PRECONDITION_REQUIRED);

        when(service.findAnyForAdministration(actor, "RND", 404))
                .thenThrow(new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        assertCode(() -> controller.publish(
                        "RND", 404, new ProjectTemplateReasonRequest("发布"), null,
                        "not-a-uuid"),
                StandardErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ProjectTemplateSnapshot snapshot(String lifecycle, long rowVersion) {
        return new ProjectTemplateSnapshot(
                TEMPLATE_ID, "RND", 2, "RND_V2", "PRODUCT_DEVELOPMENT", "产品研发 V2",
                lifecycle, rowVersion,
                lifecycle.equals("DRAFT") ? null : Instant.parse("2026-08-18T04:00:00Z"),
                null, List.of(), List.of(), List.of());
    }

    private static void assertCode(Runnable action, StandardErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(expected));
    }
}
