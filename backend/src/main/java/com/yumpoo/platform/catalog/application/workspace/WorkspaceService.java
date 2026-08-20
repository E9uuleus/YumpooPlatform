package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
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
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final String CREATED_EVENT = "catalog.workspace_created";
    private static final String UPDATED_EVENT = "catalog.workspace_updated";
    private static final String ARCHIVED_EVENT = "catalog.workspace_archived";
    private static final String RESTORED_EVENT = "catalog.workspace_restored";

    private final WorkspaceRepository repository;
    private final IdempotentCommandExecutor idempotentCommandExecutor;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkspaceService(
            WorkspaceRepository repository,
            IdempotentCommandExecutor idempotentCommandExecutor,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.idempotentCommandExecutor = idempotentCommandExecutor;
        this.eventPort = eventPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceView> findAll(CurrentActor actor, WorkspaceListStatus status) {
        requireActiveActor(actor);
        Objects.requireNonNull(status, "status must not be null");
        if (status.includeArchived()) {
            requireCompanyAdmin(actor);
        }
        return repository.findAll(actor.companyId(), status).stream()
                .map(WorkspaceView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceView findVisible(CurrentActor actor, UUID workspaceId) {
        requireActiveActor(actor);
        Workspace workspace = required(actor.companyId(), workspaceId);
        if (workspace.status() == WorkspaceStatus.ARCHIVED
                && !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        return WorkspaceView.from(workspace);
    }

    @Transactional(readOnly = true)
    public WorkspaceView findForAdministration(CurrentActor actor, UUID workspaceId) {
        requireCompanyAdmin(actor);
        return WorkspaceView.from(required(actor.companyId(), workspaceId));
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceView> findActive(UUID companyId, UUID workspaceId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return repository.findActiveById(companyId, workspaceId).map(WorkspaceView::from);
    }

    public IdempotencyExecutionResult create(WorkspaceCreateCommand command) {
        requireCompanyAdmin(command.actor());
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(), "POST", "createWorkspace", command.idempotencyKey()),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(idempotency, () -> {
            Instant now = clock.instant();
            Workspace workspace = Workspace.create(
                    UUID.randomUUID(), command.actor().companyId(), command.code(), command.name(),
                    command.description(), command.sortOrder(), command.actor().userId(), now);
            if (!repository.insert(workspace)) {
                throw ApplicationException.validation(new FieldViolation(
                        "code", "ALREADY_EXISTS", "Workspace 编码已存在"));
            }
            appendCreated(workspace, command.actor());
            return stored(201, workspace);
        });
    }

    @Transactional
    public WorkspaceView update(WorkspaceUpdateCommand command) {
        requireCompanyAdmin(command.actor());
        Workspace before = required(command.actor().companyId(), command.workspaceId());
        requireVersion(before, command.expectedRowVersion());
        if (before.hasSameDetails(command.name(), command.description(), command.sortOrder())) {
            return WorkspaceView.from(before);
        }

        Workspace candidate = before.updateDetails(
                command.name(), command.description(), command.sortOrder(),
                command.actor().userId(), clock.instant());
        Workspace after = repository.updateDetails(candidate, command.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(command.actor().companyId(), command.workspaceId(),
                        command.expectedRowVersion()));
        appendUpdated(before, after, command.actor());
        return WorkspaceView.from(after);
    }

    public IdempotencyExecutionResult archive(WorkspaceLifecycleCommand command) {
        requireCompanyAdmin(command.actor());
        return changeStatus(command, WorkspaceStatus.ACTIVE, WorkspaceStatus.ARCHIVED, ARCHIVED_EVENT);
    }

    public IdempotencyExecutionResult restore(WorkspaceLifecycleCommand command) {
        requireCompanyAdmin(command.actor());
        return changeStatus(command, WorkspaceStatus.ARCHIVED, WorkspaceStatus.ACTIVE, RESTORED_EVENT);
    }

    private IdempotencyExecutionResult changeStatus(
            WorkspaceLifecycleCommand command,
            WorkspaceStatus expectedStatus,
            WorkspaceStatus targetStatus,
            String eventType
    ) {
        String routeKey = targetStatus == WorkspaceStatus.ARCHIVED
                ? "archiveWorkspace" : "restoreWorkspace";
        IdempotencyCommand idempotency = new IdempotencyCommand(
                new IdempotencyScope(
                        command.actor().userId(), "POST", routeKey, command.idempotencyKey()),
                command.requestHash()
        );
        return idempotentCommandExecutor.execute(idempotency, () -> {
            Workspace before = required(command.actor().companyId(), command.workspaceId());
            requireVersion(before, command.expectedRowVersion());
            if (before.status() != expectedStatus) {
                throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
            }
            Workspace candidate = before.changeStatus(
                    targetStatus, command.actor().userId(), clock.instant());
            Workspace after = repository.changeStatus(
                            candidate, expectedStatus, command.expectedRowVersion())
                    .orElseThrow(() -> conditionalLifecycleFailure(
                            command.actor().companyId(), command.workspaceId(),
                            command.expectedRowVersion(), expectedStatus));
            appendLifecycle(eventType, before, after, command.actor());
            return stored(200, after);
        });
    }

    private void appendCreated(Workspace workspace, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", workspace.id());
        payload.put("code", workspace.code());
        payload.put("name", workspace.name());
        payload.put("sortOrder", workspace.sortOrder());
        payload.put("status", workspace.status());
        append(CREATED_EVENT, workspace, actor, payload);
    }

    private void appendUpdated(Workspace before, Workspace after, CurrentActor actor) {
        List<String> changedFields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            changedFields.add("name");
        }
        if (!Objects.equals(before.description(), after.description())) {
            changedFields.add("description");
        }
        if (before.sortOrder() != after.sortOrder()) {
            changedFields.add("sortOrder");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", after.id());
        payload.put("code", after.code());
        payload.put("changedFields", changedFields);
        payload.put("oldName", before.name());
        payload.put("newName", after.name());
        payload.put("oldSortOrder", before.sortOrder());
        payload.put("newSortOrder", after.sortOrder());
        payload.put("descriptionChanged", !Objects.equals(before.description(), after.description()));
        append(UPDATED_EVENT, after, actor, payload);
    }

    private void appendLifecycle(
            String eventType,
            Workspace before,
            Workspace after,
            CurrentActor actor
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", after.id());
        payload.put("code", after.code());
        payload.put("fromStatus", before.status());
        payload.put("toStatus", after.status());
        append(eventType, after, actor, payload);
    }

    private void append(
            String eventType,
            Workspace workspace,
            CurrentActor actor,
            Map<String, Object> payload
    ) {
        eventPort.append(new EventDraft(
                eventType,
                1,
                "Workspace",
                workspace.id(),
                workspace.rowVersion(),
                workspace.companyId(),
                EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)
        ));
    }

    private StoredCommandResult stored(int status, Workspace workspace) {
        try {
            return new StoredCommandResult(
                    status,
                    objectMapper.writeValueAsString(WorkspaceView.from(workspace)),
                    workspace.id(),
                    StrongEtag.format(workspace.rowVersion())
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("workspace response serialization failed", exception);
        }
    }

    private Workspace required(UUID companyId, UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return repository.findById(companyId, workspaceId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private RuntimeException conditionalFailure(UUID companyId, UUID workspaceId, long expectedVersion) {
        Workspace current = required(companyId, workspaceId);
        requireVersion(current, expectedVersion);
        return new IllegalStateException("workspace conditional update failed without a changed condition");
    }

    private RuntimeException conditionalLifecycleFailure(
            UUID companyId,
            UUID workspaceId,
            long expectedVersion,
            WorkspaceStatus expectedStatus
    ) {
        Workspace current = required(companyId, workspaceId);
        requireVersion(current, expectedVersion);
        if (current.status() != expectedStatus) {
            return new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return new IllegalStateException("workspace lifecycle update failed without a changed condition");
    }

    private static void requireVersion(Workspace workspace, long expectedVersion) {
        if (workspace.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
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
}
