package com.yumpoo.platform.administration.application;

import com.yumpoo.platform.audit.api.SecurityAuditActor;
import com.yumpoo.platform.audit.api.SecurityAuditAppendPort;
import com.yumpoo.platform.audit.api.SecurityAuditDraft;
import com.yumpoo.platform.audit.api.SecurityAuditOutcome;
import com.yumpoo.platform.catalog.api.ProjectCreationMutation;
import com.yumpoo.platform.catalog.api.ProjectLifecycleCommandPort;
import com.yumpoo.platform.catalog.api.ProjectSnapshot;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyCommand;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyExecutionResult;
import com.yumpoo.platform.foundation.application.idempotency.IdempotencyScope;
import com.yumpoo.platform.foundation.application.idempotency.IdempotentCommandExecutor;
import com.yumpoo.platform.foundation.application.idempotency.StoredCommandResult;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshot;
import com.yumpoo.platform.identityaccess.api.ActiveUserSnapshotQuery;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;
import com.yumpoo.platform.templateworkflow.api.PublishedProjectTemplateQuery;
import com.yumpoo.platform.workitem.api.InitializeProjectContentsPort;
import com.yumpoo.platform.workitem.api.InitializedProjectContent;
import com.yumpoo.platform.workitem.api.ProjectContentInitialization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectCreationOrchestrator {

    private static final String CREATED_EVENT = "catalog.project_created";
    private static final String TEMPLATE_APPLIED_EVENT = "catalog.project_template_applied";

    private final ProjectLifecycleCommandPort projectCommandPort;
    private final ActiveUserSnapshotQuery activeUserQuery;
    private final PublishedProjectTemplateQuery publishedTemplateQuery;
    private final InitializeProjectContentsPort initializeContentsPort;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final SecurityAuditAppendPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectCreationOrchestrator(
            ProjectLifecycleCommandPort projectCommandPort,
            ActiveUserSnapshotQuery activeUserQuery,
            PublishedProjectTemplateQuery publishedTemplateQuery,
            InitializeProjectContentsPort initializeContentsPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            SecurityAuditAppendPort auditPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.projectCommandPort = projectCommandPort;
        this.activeUserQuery = activeUserQuery;
        this.publishedTemplateQuery = publishedTemplateQuery;
        this.initializeContentsPort = initializeContentsPort;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyExecutionResult create(ProjectCreationCommand command) {
        requireCompanyAdmin(command.actor());
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", "createProject",
                        command.idempotencyKey()), command.requestHash());
        return idempotentCommandExecutor.execute(idempotency, () -> executeCreation(command));
    }

    private StoredCommandResult executeCreation(ProjectCreationCommand command) {
        requireAvailableOwner(command.actor().companyId(), command.ownerUserId());
        ProjectTemplateSnapshot template = publishedTemplateQuery.findPublishedForCreation(
                        command.templateKey(), command.templateVersion())
                .orElseThrow(() -> ApplicationException.validation(new FieldViolation(
                        "templateVersion", "INVALID_TEMPLATE", "模板版本不存在或不可用于新 Project")));
        if (!template.projectType().equals(command.projectType())) {
            throw ApplicationException.validation(new FieldViolation(
                    "templateKey", "TEMPLATE_TYPE_MISMATCH", "Project 类型与模板不匹配"));
        }

        ProjectSnapshot project = projectCommandPort.create(new ProjectCreationMutation(
                command.actor().companyId(), command.code(), command.name(),
                command.description(), command.projectType(), command.ownerUserId(),
                command.templateKey(), command.templateVersion(), command.customerName(),
                command.customerReference(), command.deliverySite(), command.contactNote(),
                command.actor().userId()));

        List<InitializedProjectContent> contents = initializeContentsPort.initialize(
                new ProjectContentInitialization(project.companyId(), project.projectId(),
                        project.templateKey(), project.templateVersion(), command.actor().userId(),
                        template.contentBlueprints().stream().map(blueprint ->
                                new ProjectContentInitialization.Blueprint(
                                        blueprint.contentCode(), blueprint.displayName(),
                                        blueprint.colorToken(), blueprint.sortOrder()))
                                .toList()));
        appendAudit(project, contents.size(), command);
        appendCreated(project, contents.size(), command.actor());
        appendTemplateApplied(project, contents, command.actor());
        return stored(project);
    }

    private void appendAudit(
            ProjectSnapshot project,
            int initializedContentCount,
            ProjectCreationCommand command
    ) {
        auditPort.append(new SecurityAuditDraft(
                project.companyId(),
                "project-created:" + project.projectId(),
                "PROJECT_CREATED",
                SecurityAuditOutcome.SUCCEEDED,
                SecurityAuditActor.user(command.actor().userId(), roleNames(command.actor())),
                "PROJECT",
                project.projectId().toString(),
                null,
                null,
                objectMapper.valueToTree(safeSummary(project, initializedContentCount)),
                null,
                command.idempotencyKey(),
                command.clientType(),
                command.clientVersion(),
                clock.instant()));
    }

    private void appendCreated(ProjectSnapshot project, int contentCount, CurrentActor actor) {
        eventPort.append(new EventDraft(CREATED_EVENT, 1, "Project", project.projectId(),
                project.rowVersion(), project.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(safeSummary(project, contentCount))));
    }

    private void appendTemplateApplied(
            ProjectSnapshot project,
            List<InitializedProjectContent> contents,
            CurrentActor actor
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", project.projectId());
        payload.put("templateKey", project.templateKey());
        payload.put("templateVersion", project.templateVersion());
        payload.put("initializedContentCount", contents.size());
        payload.put("contentCodes", contents.stream().map(InitializedProjectContent::code).toList());
        eventPort.append(new EventDraft(TEMPLATE_APPLIED_EVENT, 1, "Project", project.projectId(),
                project.rowVersion(), project.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private static Map<String, Object> safeSummary(ProjectSnapshot project, int contentCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", project.projectId());
        payload.put("workspaceId", project.workspaceId());
        payload.put("code", project.code());
        payload.put("name", project.name());
        payload.put("projectType", project.projectType());
        payload.put("lifecycle", project.lifecycle());
        payload.put("ownerUserId", project.ownerUserId());
        payload.put("templateKey", project.templateKey());
        payload.put("templateVersion", project.templateVersion());
        payload.put("initializedContentCount", contentCount);
        return payload;
    }

    private StoredCommandResult stored(ProjectSnapshot project) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", project.projectId());
        body.put("workspaceId", project.workspaceId());
        body.put("code", project.code());
        body.put("name", project.name());
        body.put("description", project.description());
        body.put("projectType", project.projectType());
        body.put("lifecycle", project.lifecycle());
        body.put("ownerUserId", project.ownerUserId());
        body.put("templateKey", project.templateKey());
        body.put("templateVersion", project.templateVersion());
        body.put("customerName", project.customerName());
        body.put("customerReference", project.customerReference());
        body.put("deliverySite", project.deliverySite());
        body.put("contactNote", project.contactNote());
        body.put("rowVersion", project.rowVersion());
        try {
            return new StoredCommandResult(201, objectMapper.writeValueAsString(body),
                    project.projectId(), StrongEtag.format(project.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("project response serialization failed", exception);
        }
    }

    private void requireAvailableOwner(UUID companyId, UUID ownerUserId) {
        ActiveUserSnapshot owner = activeUserQuery.findByUserId(ownerUserId).orElse(null);
        if (owner == null || !owner.companyId().equals(companyId) || !owner.activeAndEnabled()) {
            throw ApplicationException.validation(new FieldViolation(
                    "ownerUserId", "INVALID_OWNER", "负责人必须是本企业有效成员"));
        }
    }

    private static void requireCompanyAdmin(CurrentActor actor) {
        if (actor == null) {
            throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (!actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
