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

    private final ProjectLifecycleCommandPort projectCommandPort;
    private final ActiveUserSnapshotQuery activeUserQuery;
    private final InitializeProjectContentsPort initializeContentsPort;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final SecurityAuditAppendPort auditPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectCreationOrchestrator(
            ProjectLifecycleCommandPort projectCommandPort,
            ActiveUserSnapshotQuery activeUserQuery,
            InitializeProjectContentsPort initializeContentsPort,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            SecurityAuditAppendPort auditPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.projectCommandPort = projectCommandPort;
        this.activeUserQuery = activeUserQuery;
        this.initializeContentsPort = initializeContentsPort;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.auditPort = auditPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyExecutionResult create(ProjectCreationCommand command) {
        requireActiveActor(command.actor());
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(command.actor().userId(), "POST", "createProject",
                        command.idempotencyKey()), command.requestHash());
        return idempotentCommandExecutor.execute(idempotency, () -> executeCreation(command));
    }

    private StoredCommandResult executeCreation(ProjectCreationCommand command) {
        ProjectSnapshot project = projectCommandPort.create(new ProjectCreationMutation(
                command.actor().companyId(), command.name(),
                command.description(),
                command.actor().userId()));

        List<InitializedProjectContent> contents = initializeContentsPort.initialize(
                new ProjectContentInitialization(project.companyId(), project.projectId(), command.actor().userId()));
        appendAudit(project, contents.size(), command);
        appendCreated(project, contents.size(), command.actor());
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
        eventPort.append(new EventDraft(CREATED_EVENT, 2, "Project", project.projectId(),
                project.rowVersion(), project.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(safeSummary(project, contentCount))));
    }

    private static Map<String, Object> safeSummary(ProjectSnapshot project, int contentCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", project.projectId());
        payload.put("workspaceId", project.workspaceId());
        payload.put("code", project.code());
        payload.put("name", project.name());
        payload.put("lifecycle", project.lifecycle());
        payload.put("ownerUserId", project.ownerUserId());
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
        body.put("lifecycle", project.lifecycle());
        body.put("ownerUserId", project.ownerUserId());
        body.put("rowVersion", project.rowVersion());
        try {
            return new StoredCommandResult(201, objectMapper.writeValueAsString(body),
                    project.projectId(), StrongEtag.format(project.rowVersion()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("project response serialization failed", exception);
        }
    }

    private void requireActiveActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
        ActiveUserSnapshot user = activeUserQuery.findByUserId(actor.userId()).orElse(null);
        if (user == null || !user.companyId().equals(actor.companyId()) || !user.activeAndEnabled()) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
    }

    private static Set<String> roleNames(CurrentActor actor) {
        return actor.platformRoles().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
