package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommand;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionCommandPort;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateVersionQuery;
import com.yumpoo.platform.templateworkflow.api.PublishedProjectTemplateQuery;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectTemplateGovernanceService {

    private static final String PUBLISHED_EVENT = "templateworkflow.project_template_published";
    private static final String RETIRED_EVENT = "templateworkflow.project_template_retired";

    private final PublishedProjectTemplateQuery publishedQuery;
    private final ProjectTemplateVersionQuery versionQuery;
    private final ProjectTemplateVersionCommandPort commandPort;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final SecurityAuditAppendPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectTemplateGovernanceService(
            PublishedProjectTemplateQuery publishedQuery,
            ProjectTemplateVersionQuery versionQuery,
            ProjectTemplateVersionCommandPort commandPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            SecurityAuditAppendPort auditPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.publishedQuery = publishedQuery;
        this.versionQuery = versionQuery;
        this.commandPort = commandPort;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<ProjectTemplateSnapshot> findPublished(CurrentActor actor) {
        requireActiveActor(actor);
        return publishedQuery.findAllPublished();
    }

    public ProjectTemplateSnapshot findAnyForAdministration(
            CurrentActor actor,
            String templateKey,
            int version
    ) {
        requireCompanyAdmin(actor);
        return versionQuery.findAny(templateKey, version)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    public IdempotencyExecutionResult publish(ProjectTemplateGovernanceCommand command) {
        requireCompanyAdmin(command.actor());
        return execute(command, true);
    }

    public IdempotencyExecutionResult retire(ProjectTemplateGovernanceCommand command) {
        requireCompanyAdmin(command.actor());
        return execute(command, false);
    }

    private IdempotencyExecutionResult execute(
            ProjectTemplateGovernanceCommand command,
            boolean publish
    ) {
        String operation = publish ? "projectTemplatePublish" : "projectTemplateRetire";
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(), "POST", operation, command.idempotencyKey()),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(idempotency, () -> {
            Instant changedAt = clock.instant();
            ProjectTemplateSnapshot before = versionQuery.findAny(command.templateKey(), command.version())
                    .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
            ProjectTemplateVersionCommand mutation = new ProjectTemplateVersionCommand(
                    command.templateKey(), command.version(), command.expectedRowVersion(),
                    command.actor().userId(), command.reason(), changedAt);
            ProjectTemplateSnapshot after = publish
                    ? commandPort.publish(mutation)
                    : commandPort.retire(mutation);
            EventActor eventActor = EventActor.adminOverride(
                    command.actor().userId(), command.reason());
            appendEvent(publish ? PUBLISHED_EVENT : RETIRED_EVENT, before, after, eventActor,
                    command.reason(), command.actor().companyId());
            appendAudit(publish ? "PROJECT_TEMPLATE_PUBLISHED" : "PROJECT_TEMPLATE_RETIRED",
                    before, after, command);
            return stored(after);
        });
    }

    private void appendEvent(
            String eventType,
            ProjectTemplateSnapshot before,
            ProjectTemplateSnapshot after,
            EventActor actor,
            String reason,
            java.util.UUID companyId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("templateVersionId", after.templateVersionId());
        payload.put("templateKey", after.templateKey());
        payload.put("version", after.version());
        payload.put("versionCode", after.versionCode());
        payload.put("projectType", after.projectType());
        payload.put("fromStatus", before.lifecycleStatus());
        payload.put("toStatus", after.lifecycleStatus());
        payload.put("reasonReference", reason);
        eventPort.append(new EventDraft(
                eventType, 1, "ProjectTemplateVersion", after.templateVersionId(),
                after.rowVersion(), companyId, actor, objectMapper.valueToTree(payload)));
    }

    private void appendAudit(
            String action,
            ProjectTemplateSnapshot before,
            ProjectTemplateSnapshot after,
            ProjectTemplateGovernanceCommand command
    ) {
        auditPort.append(new SecurityAuditDraft(
                command.actor().companyId(),
                "project-template:" + after.templateVersionId() + ":" + after.rowVersion(),
                action,
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(command.actor().userId(), roleNames(command.actor())),
                "PROJECT_TEMPLATE_VERSION",
                after.templateVersionId().toString(),
                command.reason(),
                objectMapper.valueToTree(Map.of(
                        "templateKey", before.templateKey(),
                        "version", before.version(),
                        "lifecycleStatus", before.lifecycleStatus(),
                        "rowVersion", before.rowVersion())),
                objectMapper.valueToTree(Map.of(
                        "templateKey", after.templateKey(),
                        "version", after.version(),
                        "lifecycleStatus", after.lifecycleStatus(),
                        "rowVersion", after.rowVersion())),
                null,
                command.idempotencyKey(),
                command.clientType(),
                command.clientVersion(),
                clock.instant()
        ));
    }

    private StoredCommandResult stored(ProjectTemplateSnapshot snapshot) {
        try {
            return new StoredCommandResult(
                    200,
                    objectMapper.writeValueAsString(snapshot),
                    snapshot.templateVersionId(),
                    StrongEtag.format(snapshot.rowVersion())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("project template response serialization failed", exception);
        }
    }

    private static void requireActiveActor(CurrentActor actor) {
        if (actor == null) {
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private static void requireCompanyAdmin(CurrentActor actor) {
        requireActiveActor(actor);
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
