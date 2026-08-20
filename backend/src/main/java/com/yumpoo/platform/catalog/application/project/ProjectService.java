package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.catalog.domain.project.ProjectType;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageResponse;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import com.yumpoo.platform.foundation.application.event.EventActor;
import com.yumpoo.platform.foundation.application.event.EventDraft;
import com.yumpoo.platform.foundation.application.event.TransactionalEventPort;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshot;
import com.yumpoo.platform.identityaccess.api.MinimalUserSnapshotQuery;
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
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository repository;
    private final MinimalUserSnapshotQuery userQuery;
    private final TransactionalEventPort eventPort;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProjectService(ProjectRepository repository, MinimalUserSnapshotQuery userQuery,
                          TransactionalEventPort eventPort, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.userQuery = userQuery;
        this.eventPort = eventPort;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OffsetPageResponse<ProjectSummary> findAll(CurrentActor actor, UUID workspaceId,
            ProjectTypeFilter projectType, ProjectLifecycleFilter lifecycle, OffsetPageRequest page) {
        requireActor(actor);
        ProjectPageResult result = repository.findVisible(actor, workspaceId,
                projectType == null ? null : projectType.toDomain(), lifecycle, page);
        Map<UUID, MinimalUserSnapshot> owners = userQuery.findByUserIds(actor.companyId(),
                result.items().stream().map(row -> row.project().ownerUserId()).distinct().toList());
        List<ProjectSummary> items = result.items().stream()
                .map(row -> summary(actor, row, displayName(owners, row.project().ownerUserId())))
                .toList();
        return OffsetPageResponse.of(items, page, result.totalElements());
    }

    @Transactional(readOnly = true)
    public ProjectDetail findVisible(CurrentActor actor, UUID projectId) {
        ProjectQueryRow row = requiredVisible(actor, projectId);
        String ownerName = userQuery.findByUserId(actor.companyId(), row.project().ownerUserId())
                .map(MinimalUserSnapshot::displayName).orElse("-");
        return detail(actor, row, ownerName);
    }

    @Transactional
    public ProjectDetail update(ProjectUpdateCommand command) {
        ProjectQueryRow row = requiredVisible(command.actor(), command.projectId());
        Project before = row.project();
        if (!before.ownerUserId().equals(command.actor().userId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        requireVersion(before, command.expectedRowVersion());
        if (before.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        if (before.hasSameDetails(command.name(), command.description(), command.customerName(),
                command.customerReference(), command.deliverySite(), command.contactNote())) {
            return detail(command.actor(), row, ownerName(command.actor(), before.ownerUserId()));
        }
        Project candidate = before.updateDetails(command.name(), command.description(),
                command.customerName(), command.customerReference(), command.deliverySite(),
                command.contactNote(), command.actor().userId(), clock.instant());
        Project after = repository.updateDetails(candidate, command.expectedRowVersion())
                .orElseThrow(() -> conditionalFailure(command.actor(), command.projectId(),
                        command.expectedRowVersion()));
        appendUpdated(before, after, command.actor());
        return detail(command.actor(), new ProjectQueryRow(after, row.workspaceCode(),
                row.workspaceName(), row.actorAccess()), ownerName(command.actor(), after.ownerUserId()));
    }

    private void appendUpdated(Project before, Project after, CurrentActor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", after.id());
        payload.put("code", after.code());
        payload.put("lifecycle", after.lifecycle().name());
        payload.put("changedFields", changedFields(before, after));
        eventPort.append(new EventDraft("catalog.project_updated", 1, "Project", after.id(),
                after.rowVersion(), after.companyId(), EventActor.user(actor.userId()),
                objectMapper.valueToTree(payload)));
    }

    private static List<String> changedFields(Project before, Project after) {
        List<String> fields = new ArrayList<>();
        if (!before.name().equals(after.name())) fields.add("name");
        if (!Objects.equals(before.description(), after.description())) fields.add("description");
        if (!Objects.equals(before.customerName(), after.customerName())) fields.add("customerName");
        if (!Objects.equals(before.customerReference(), after.customerReference())) fields.add("customerReference");
        if (!Objects.equals(before.deliverySite(), after.deliverySite())) fields.add("deliverySite");
        if (!Objects.equals(before.contactNote(), after.contactNote())) fields.add("contactNote");
        return List.copyOf(fields);
    }

    private ProjectQueryRow requiredVisible(CurrentActor actor, UUID projectId) {
        requireActor(actor);
        return repository.findVisibleById(actor, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private RuntimeException conditionalFailure(CurrentActor actor, UUID projectId, long version) {
        Project current = requiredVisible(actor, projectId).project();
        requireVersion(current, version);
        if (current.lifecycle() == ProjectLifecycle.ARCHIVED) {
            return new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return new IllegalStateException("project conditional update failed without changed condition");
    }

    private String ownerName(CurrentActor actor, UUID ownerId) {
        return userQuery.findByUserId(actor.companyId(), ownerId)
                .map(MinimalUserSnapshot::displayName).orElse("-");
    }

    private static String displayName(Map<UUID, MinimalUserSnapshot> owners, UUID ownerId) {
        MinimalUserSnapshot owner = owners.get(ownerId);
        return owner == null ? "-" : owner.displayName();
    }

    private static ProjectSummary summary(CurrentActor actor, ProjectQueryRow row, String ownerName) {
        Project project = row.project();
        return new ProjectSummary(project.id(), project.workspaceId(), row.workspaceCode(),
                row.workspaceName(), project.code(), project.name(), project.projectType().name(),
                project.lifecycle().name(), project.ownerUserId(), ownerName, access(row),
                capabilities(actor, project), project.rowVersion(), StrongEtag.format(project.rowVersion()));
    }

    private static ProjectDetail detail(CurrentActor actor, ProjectQueryRow row, String ownerName) {
        Project project = row.project();
        return new ProjectDetail(project.id(), project.workspaceId(), row.workspaceCode(),
                row.workspaceName(), project.code(), project.name(), project.description(),
                project.projectType().name(), project.lifecycle().name(), project.ownerUserId(), ownerName,
                project.templateKey(), project.templateVersion(), project.customerName(),
                project.customerReference(), project.deliverySite(), project.contactNote(), access(row),
                capabilities(actor, project), project.rowVersion(), StrongEtag.format(project.rowVersion()),
                project.createdAt(), project.updatedAt(), project.activatedAt(), project.archivedAt());
    }

    private static ProjectActorAccess access(ProjectQueryRow row) {
        return switch (row.actorAccess()) {
            case OWNER -> ProjectActorAccess.OWNER;
            case MEMBER -> ProjectActorAccess.MEMBER;
            case COMPANY_ADMIN_READ_ONLY -> ProjectActorAccess.COMPANY_ADMIN;
        };
    }

    private static ProjectCapabilities capabilities(CurrentActor actor, Project project) {
        boolean owner = project.ownerUserId().equals(actor.userId());
        boolean admin = actor.hasRole(PlatformRoleCode.COMPANY_ADMIN);
        boolean mutable = project.lifecycle() != ProjectLifecycle.ARCHIVED;
        return new ProjectCapabilities(owner && mutable,
                owner && project.lifecycle() == ProjectLifecycle.DRAFT,
                (owner || admin) && mutable, admin && mutable);
    }

    private static void requireVersion(Project project, long version) {
        if (project.rowVersion() != version) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static void requireActor(CurrentActor actor) {
        if (actor == null) throw new ApplicationException(StandardErrorCode.AUTHENTICATION_REQUIRED);
    }
}
