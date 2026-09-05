package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
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
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommand;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommandPort;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.templateworkflow.api.PublishedProjectTemplateQuery;
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

class ProjectTemplateGovernanceServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("16000000-0000-4000-8000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("16000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-18T04:00:00Z");

    private PublishedProjectTemplateQuery publishedQuery;
    private ProjectTemplateVersionQuery versionQuery;
    private ProjectTemplateVersionCommandPort commandPort;
    private IdempotentCommandExecutor executor;
    private TransactionalEventPort eventPort;
    private SecurityAuditAppendPort auditPort;
    private ProjectTemplateGovernanceService service;

    @BeforeEach
    void setUp() {
        publishedQuery = mock(PublishedProjectTemplateQuery.class);
        versionQuery = mock(ProjectTemplateVersionQuery.class);
        commandPort = mock(ProjectTemplateVersionCommandPort.class);
        executor = mock(IdempotentCommandExecutor.class);
        eventPort = mock(TransactionalEventPort.class);
        auditPort = mock(SecurityAuditAppendPort.class);
        service = new ProjectTemplateGovernanceService(
                publishedQuery, versionQuery, commandPort, executor, eventPort, auditPort,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPersistsOneMutationEventAuditAndReplayableStrongEtagResult() {
        ProjectTemplateSnapshot draft = snapshot("DRAFT", 0, null, null);
        ProjectTemplateSnapshot published = snapshot("PUBLISHED", 1, NOW, null);
        when(versionQuery.findAny("RND", 2)).thenReturn(Optional.of(draft));
        when(commandPort.publish(any(ProjectTemplateVersionCommand.class))).thenReturn(published);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResult> callback = invocation.getArgument(1);
            return IdempotencyExecutionResult.executed(callback.get());
        });

        IdempotencyExecutionResult result = service.publish(command(companyAdmin(), 0, "发布首批 V2"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.result().httpStatus()).isEqualTo(200);
        assertThat(result.result().etag()).isEqualTo("\"1\"");
        assertThat(result.result().responseJson()).contains("RND_V2", "PUBLISHED");

        ArgumentCaptor<EventDraft> event = ArgumentCaptor.forClass(EventDraft.class);
        verify(eventPort).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("templateworkflow.project_template_published");
        assertThat(event.getValue().eventVersion()).isEqualTo(1);
        assertThat(event.getValue().companyId()).isEqualTo(COMPANY_ID);
        assertThat(event.getValue().payload().get("fromStatus").stringValue()).isEqualTo("DRAFT");
        assertThat(event.getValue().payload().get("toStatus").stringValue()).isEqualTo("PUBLISHED");
        assertThat(event.getValue().payload().get("reasonReference").stringValue()).isEqualTo("发布首批 V2");

        ArgumentCaptor<SecurityAuditDraft> audit = ArgumentCaptor.forClass(SecurityAuditDraft.class);
        verify(auditPort).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("PROJECT_TEMPLATE_PUBLISHED");
        assertThat(audit.getValue().companyId()).isEqualTo(COMPANY_ID);
    }

    @Test
    void onlyCompanyAdminCanReadAnyLifecycleOrMutate() {
        CurrentActor member = new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());

        assertDenied(() -> service.findAnyForAdministration(member, "RND", 2));
        assertDenied(() -> service.publish(command(member, 0, "越权发布")));
        verify(versionQuery, never()).findAny(any(), any(Integer.class));
        verify(commandPort, never()).publish(any());
    }

    @Test
    void activeMemberCanListPublishedTemplatesButAnonymousCannot() {
        CurrentActor member = new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of());
        when(publishedQuery.findAllPublished()).thenReturn(List.of(snapshot("PUBLISHED", 1, NOW, null)));

        assertThat(service.findPublished(member)).hasSize(1);
        assertThatThrownBy(() -> service.findPublished(null))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.AUTHENTICATION_REQUIRED));
    }

    private static ProjectTemplateGovernanceCommand command(
            CurrentActor actor,
            long expectedVersion,
            String reason
    ) {
        return new ProjectTemplateGovernanceCommand(
                actor, "RND", 2, expectedVersion, reason, UUID.randomUUID(),
                new RequestHash("a".repeat(64)), "WEB", "test");
    }

    private static CurrentActor companyAdmin() {
        return new CurrentActor(ACTOR_ID, COMPANY_ID, 0, Set.of(PlatformRoleCode.COMPANY_ADMIN));
    }

    private static ProjectTemplateSnapshot snapshot(
            String lifecycle,
            long rowVersion,
            Instant publishedAt,
            Instant retiredAt
    ) {
        return new ProjectTemplateSnapshot(
                TEMPLATE_ID, "RND", 2, "RND_V2", "PRODUCT_DEVELOPMENT", "产品研发 V2",
                lifecycle, rowVersion, publishedAt, retiredAt,
                List.of(new ProjectTemplateSnapshot.ContentBlueprint(
                        "REQUIREMENTS", "需求", "BRIGHT_BLUE", 10)),
                List.of(new ProjectTemplateSnapshot.WorkflowStatus(
                        "BACKLOG", "待规划", "TODO", 10, true, false)),
                List.of());
    }

    private static void assertDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(StandardErrorCode.ACCESS_DENIED));
    }
}
