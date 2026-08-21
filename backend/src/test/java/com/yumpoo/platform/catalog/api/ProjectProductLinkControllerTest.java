package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.LinkView;
import com.yumpoo.platform.catalog.application.project.ProjectProductLinkService;
import com.yumpoo.platform.foundation.api.http.IdempotencyKeyParser;
import com.yumpoo.platform.foundation.api.http.IdempotencyRequestHasher;
import com.yumpoo.platform.foundation.api.http.IfMatchParser;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectProductLinkControllerTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("27000000-0000-4000-8000-000000000201");
    private static final UUID PROJECT = UUID.fromString("27000000-0000-4000-8000-000000000202");
    private static final UUID PRODUCT = UUID.fromString("27000000-0000-4000-8000-000000000203");
    private static final UUID LINK = UUID.fromString("27000000-0000-4000-8000-000000000204");

    private ProjectProductLinkService service;
    private ProjectProductLinkController controller;

    @BeforeEach
    void setUp() {
        CurrentActor actor = new CurrentActor(ACTOR, COMPANY, 0, Set.of());
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        when(actors.requiredActive()).thenReturn(actor);
        service = mock(ProjectProductLinkService.class);
        controller = new ProjectProductLinkController(actors, service, new IfMatchParser(),
                new IdempotencyKeyParser(), new IdempotencyRequestHasher(), new ObjectMapper());
    }

    @Test
    void createReturns201LocationAndStrongRelationEtag() {
        when(service.create(any())).thenReturn(IdempotencyExecutionResult.executed(
                new StoredCommandResult(201, "{\"id\":\"" + LINK + "\"}", LINK, "\"0\"")));

        ResponseEntity<String> response = controller.create(PROJECT,
                new ProjectProductLinkCreateRequest(PRODUCT, "DEVELOPMENT", true),
                UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(response.getHeaders().getLocation()).hasToString(
                "/api/v1/projects/" + PROJECT + "/products/" + LINK);
    }

    @Test
    void patchRequiresVisibleResourceBeforeIfMatchAndReturnsNewEtag() {
        LinkView changed = view(3, true, "ACTIVE");
        when(service.changePrimary(any())).thenReturn(changed);

        assertThatThrownBy(() -> controller.update(PROJECT, LINK,
                new ProjectProductLinkUpdateRequest(true), null))
                .isInstanceOfSatisfying(ApplicationException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(StandardErrorCode.PRECONDITION_REQUIRED));

        ResponseEntity<LinkView> response = controller.update(PROJECT, LINK,
                new ProjectProductLinkUpdateRequest(true), "\"2\"");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"3\"");
        verify(service).changePrimary(any());
    }

    @Test
    void deleteAllowsMissingBodyAndReturnsStoredRemovedFact() {
        when(service.remove(any())).thenReturn(IdempotencyExecutionResult.executed(
                new StoredCommandResult(200, "{\"status\":\"REMOVED\"}", LINK, "\"4\"")));

        ResponseEntity<String> response = controller.remove(PROJECT, LINK, null, "\"3\"",
                UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
        assertThat(response.getHeaders().getLocation()).isNull();
    }

    private static LinkView view(long version, boolean primary, String status) {
        Instant now = Instant.parse("2026-08-21T12:00:00Z");
        return new LinkView(LINK, PROJECT, PRODUCT, "PRODUCT", "Product", "ACTIVE",
                "DEVELOPMENT", primary, status, now, ACTOR, now, ACTOR, null, null,
                null, version, '"' + Long.toString(version) + '"');
    }
}
