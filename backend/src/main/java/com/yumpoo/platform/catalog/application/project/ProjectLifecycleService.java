package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus;
import com.yumpoo.platform.catalog.application.workspace.WorkspaceRepository;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class ProjectLifecycleService {

    private final ProjectRepository projects;
    private final ProjectMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public ProjectLifecycleService(ProjectRepository projects,
                                   ProjectMembershipRepository memberships,
                                   WorkspaceRepository workspaces, Clock clock) {
        this.projects = projects;
        this.memberships = memberships;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectActivationState lockForActivation(ProjectActivationCommand command) {
        Project project = projects.lockById(command.companyId(), command.projectId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireOwnerAndVersion(project, command);
        if (project.lifecycle() != ProjectLifecycle.DRAFT) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        boolean membershipActive = memberships.find(project.companyId(), project.id(),
                        project.ownerUserId()).map(member -> member.status() == ProjectMembershipStatus.ACTIVE)
                .orElse(false);
        return new ProjectActivationState(snapshot(project), membershipActive);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot activate(ProjectActivationCommand command) {
        Project before = projects.lockById(command.companyId(), command.projectId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        requireOwnerAndVersion(before, command);
        if (before.lifecycle() != ProjectLifecycle.DRAFT) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        Project candidate = before.activate(command.actorUserId(), clock.instant());
        Project after = projects.activate(candidate, command.expectedRowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return snapshot(after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot lockForArchive(ProjectArchiveCommand command) {
        Project project = requiredLocked(command.companyId(), command.projectId());
        requireVersion(project, command.expectedRowVersion());
        if (command.ownerRequired() && !project.ownerUserId().equals(command.actorUserId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        requireLifecycle(project, ProjectLifecycle.ACTIVE);
        return snapshot(project);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot archive(ProjectArchiveCommand command) {
        Project before = requiredLocked(command.companyId(), command.projectId());
        requireVersion(before, command.expectedRowVersion());
        if (command.ownerRequired() && !before.ownerUserId().equals(command.actorUserId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        requireLifecycle(before, ProjectLifecycle.ACTIVE);
        Project after = projects.archive(before.archive(command.actorUserId(), clock.instant()),
                        command.expectedRowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return snapshot(after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectRestoreState lockForRestore(ProjectRestoreCommand command) {
        Project project = requiredLocked(command.companyId(), command.projectId());
        requireVersion(project, command.expectedRowVersion());
        requireLifecycle(project, ProjectLifecycle.ARCHIVED);
        boolean membershipActive = memberships.find(project.companyId(), project.id(),
                        project.ownerUserId()).map(member -> member.status() == ProjectMembershipStatus.ACTIVE)
                .orElse(false);
        return new ProjectRestoreState(snapshot(project), membershipActive);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot reopen(ProjectRestoreCommand command) {
        Project before = requiredLocked(command.companyId(), command.projectId());
        requireVersion(before, command.expectedRowVersion());
        requireLifecycle(before, ProjectLifecycle.ARCHIVED);
        if (workspaces.findActiveByIdForShare(before.companyId(), before.workspaceId()).isEmpty()) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORKSPACE_UNAVAILABLE");
        }
        Project after = projects.reopen(before.reopen(command.actorUserId(), clock.instant()),
                        command.expectedRowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return snapshot(after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot moveWorkspace(ProjectWorkspaceMoveCommand command) {
        Project before = requiredLocked(command.companyId(), command.projectId());
        requireVersion(before, command.expectedRowVersion());
        if (before.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        if (before.workspaceId().equals(command.targetWorkspaceId())) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "WORKSPACE_UNCHANGED");
        }
        if (workspaces.findActiveByIdForShare(before.companyId(), command.targetWorkspaceId()).isEmpty()) {
            throw ApplicationException.validation(new com.yumpoo.platform.foundation.application.error.FieldViolation(
                    "targetWorkspaceId", "INVALID_WORKSPACE", "目标 Workspace 必须是本企业 ACTIVE Workspace"));
        }
        Project after = projects.moveWorkspace(before.moveToWorkspace(command.targetWorkspaceId(),
                        command.actorUserId(), clock.instant()), command.expectedRowVersion())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.VERSION_CONFLICT));
        return snapshot(after);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot lockForWorkspaceMove(ProjectWorkspaceMoveCommand command) {
        Project project = requiredLocked(command.companyId(), command.projectId());
        requireVersion(project, command.expectedRowVersion());
        if (project.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
        return snapshot(project);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot lockForNewFact(java.util.UUID companyId,
                                                      java.util.UUID projectId) {
        Project project = projects.lockByIdForShare(companyId, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
        if (project.lifecycle() == ProjectLifecycle.ARCHIVED) {
            throw ApplicationException.withReason(StandardErrorCode.INVALID_STATE_TRANSITION,
                    "PROJECT_ARCHIVED");
        }
        return snapshot(project);
    }

    private Project requiredLocked(java.util.UUID companyId, java.util.UUID projectId) {
        return projects.lockById(companyId, projectId)
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.RESOURCE_NOT_FOUND));
    }

    private static void requireVersion(Project project, long expectedVersion) {
        if (project.rowVersion() != expectedVersion) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static void requireLifecycle(Project project, ProjectLifecycle expected) {
        if (project.lifecycle() != expected) {
            throw new ApplicationException(StandardErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static void requireOwnerAndVersion(Project project, ProjectActivationCommand command) {
        if (!project.ownerUserId().equals(command.actorUserId())) {
            throw new ApplicationException(StandardErrorCode.ACCESS_DENIED);
        }
        if (project.rowVersion() != command.expectedRowVersion()) {
            throw new ApplicationException(StandardErrorCode.VERSION_CONFLICT);
        }
    }

    private static ProjectApplicationSnapshot snapshot(Project project) {
        return new ProjectApplicationSnapshot(project.id(), project.companyId(), project.workspaceId(),
                project.code(), project.name(), project.description(), project.projectType().name(),
                project.lifecycle().name(), project.ownerUserId(), project.templateKey(),
                project.templateVersion(), project.customerName(), project.customerReference(),
                project.deliverySite(), project.contactNote(), project.rowVersion());
    }
}
