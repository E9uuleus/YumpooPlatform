package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectMembership;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class ProjectCreationService {
    private final ProjectRepository projects;
    private final ProjectMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public ProjectCreationService(ProjectRepository projects, ProjectMembershipRepository memberships,
            WorkspaceRepository workspaces, Clock clock) {
        this.projects = projects; this.memberships = memberships; this.workspaces = workspaces; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot create(ProjectCreateCommand command) {
        UUID workspaceId = workspaces.findMainForUpdate(command.companyId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.INTERNAL_ERROR)).id();
        long sequence = projects.nextGeneratedCodeSequence(command.companyId());
        Project project = Project.create(UUID.randomUUID(), command.companyId(), workspaceId,
                "P%03d".formatted(sequence), command.name(), command.description(), command.actorUserId(),
                command.actorUserId(), clock.instant());
        if (!projects.insert(project)) throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        if (!memberships.insert(ProjectMembership.activeOwner(UUID.randomUUID(), project.companyId(),
                project.id(), project.ownerUserId(), command.actorUserId(), project.createdAt()))) {
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
        return new ProjectApplicationSnapshot(project.id(), project.companyId(), project.workspaceId(),
                project.code(), project.name(), project.description(), project.lifecycle().name(),
                project.ownerUserId(), project.rowVersion());
    }
}
