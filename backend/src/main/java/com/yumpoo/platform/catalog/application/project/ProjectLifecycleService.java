package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectLifecycle;
import com.yumpoo.platform.catalog.domain.project.ProjectMembershipStatus;
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
    private final Clock clock;

    public ProjectLifecycleService(ProjectRepository projects,
                                   ProjectMembershipRepository memberships, Clock clock) {
        this.projects = projects;
        this.memberships = memberships;
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
