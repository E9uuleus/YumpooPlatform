package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;
import com.yumpoo.platform.catalog.application.project.ProjectRepository;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkspaceService {

    private static final String UPDATED_EVENT = "catalog.workspace_updated";

    private final WorkspaceRepository repository;
    private final ProjectRepository projectRepository;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WorkspaceService(
            WorkspaceRepository repository,
            ProjectRepository projectRepository,
            TransactionalEventPort eventPort,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.projectRepository = projectRepository;
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
        List<Workspace> workspaces = repository.findAll(actor.companyId(), status);
        Map<UUID, Long> counts = projectRepository.countVisibleCurrentByWorkspace(actor,
                workspaces.stream().map(Workspace::id).toList());
        return workspaces.stream().map(workspace -> WorkspaceView.from(workspace,
                counts.getOrDefault(workspace.id(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceView findVisible(CurrentActor actor, UUID workspaceId) {
        requireActiveActor(actor);
        Workspace workspace = required(actor.companyId(), workspaceId);
        if (workspace.status() == WorkspaceStatus.ARCHIVED
                && !actor.hasRole(PlatformRoleCode.COMPANY_ADMIN)) {
            throw new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND);
        }
        return WorkspaceView.from(workspace, visibleProjectCount(actor, workspace.id()));
    }

    @Transactional(readOnly = true)
    public WorkspaceView findForAdministration(CurrentActor actor, UUID workspaceId) {
        requireCompanyAdmin(actor);
        Workspace workspace = required(actor.companyId(), workspaceId);
        return WorkspaceView.from(workspace, visibleProjectCount(actor, workspace.id()));
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceView> findActive(UUID companyId, UUID workspaceId) {
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return repository.findActiveById(companyId, workspaceId).map(WorkspaceView::from);
    }

    @Transactional
    public WorkspaceView update(WorkspaceUpdateCommand command) {
        requireCompanyAdmin(command.actor());
        Workspace before = required(command.actor().companyId(), command.workspaceId());
        requireVersion(before, command.expectedRowVersion());
        if (before.hasSameDetails(command.name(), command.description())) {
            return WorkspaceView.from(before);
        }

        Workspace candidate = before.updateDetails(
                command.name(), command.description(),
                command.actor().userId(), clock.instant());
        Workspace after = repository.updateDetails(candidate, command.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(command.actor().companyId(), command.workspaceId(),
                        command.expectedRowVersion()));
        appendUpdated(before, after, command.actor());
        return WorkspaceView.from(after);
    }

    private void appendUpdated(Workspace before, Workspace after, CurrentActor actor) {
        List<String> changedFields = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            changedFields.add("name");
        }
        if (!Objects.equals(before.description(), after.description())) {
            changedFields.add("description");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", after.id());
        payload.put("code", after.code());
        payload.put("changedFields", changedFields);
        payload.put("oldName", before.name());
        payload.put("newName", after.name());
        payload.put("descriptionChanged", !Objects.equals(before.description(), after.description()));
        append(UPDATED_EVENT, after, actor, payload);
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

    private Workspace required(UUID companyId, UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        return repository.findById(companyId, workspaceId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private long visibleProjectCount(CurrentActor actor, UUID workspaceId) {
        return projectRepository.countVisibleCurrentByWorkspace(actor, List.of(workspaceId))
                .getOrDefault(workspaceId, 0L);
    }

    private RuntimeException conditionalFailure(UUID companyId, UUID workspaceId, long expectedVersion) {
        Workspace current = required(companyId, workspaceId);
        requireVersion(current, expectedVersion);
        return new IllegalStateException("workspace conditional update failed without a changed condition");
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
