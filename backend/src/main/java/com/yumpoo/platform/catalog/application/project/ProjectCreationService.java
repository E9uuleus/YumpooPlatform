package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceRepository;
import com.yumpoo.platform.catalog.domain.project.Project;
import com.yumpoo.platform.catalog.domain.project.ProjectMembership;
import com.yumpoo.platform.catalog.domain.project.ProjectType;
import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;
import com.yumpoo.platform.foundation.application.error.StandardErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ProjectCreationService {

    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final WorkspaceRepository workspaceRepository;
    private final Clock clock;

    public ProjectCreationService(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            WorkspaceRepository workspaceRepository,
            Clock clock
    ) {
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.workspaceRepository = workspaceRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectApplicationSnapshot create(ProjectCreateCommand command) {
        UUID workspaceId = workspaceRepository.findMainForShare(command.companyId())
                .orElseThrow(() -> new ApplicationException(StandardErrorCode.INTERNAL_ERROR))
                .id();

        Instant now = clock.instant();
        Project project;
        try {
            project = Project.create(UUID.randomUUID(), command.companyId(), workspaceId,
                    command.code(), command.name(), command.description(),
                    ProjectType.valueOf(command.projectType()), command.ownerUserId(),
                    command.templateKey(), command.templateVersion(), command.customerName(),
                    command.customerReference(), command.deliverySite(), command.contactNote(),
                    command.actorUserId(), now);
        } catch (IllegalArgumentException exception) {
            throw new ApplicationException(StandardErrorCode.VALIDATION_FAILED,
                    StandardErrorCode.VALIDATION_FAILED.defaultMessage(),
                    java.util.List.of(new FieldViolation(
                            "templateKey", "TEMPLATE_TYPE_MISMATCH", "Project 类型与模板不匹配")));
        }
        if (!projectRepository.insert(project)) {
            throw ApplicationException.validation(new FieldViolation(
                    "code", "ALREADY_EXISTS", "Project 编码已存在"));
        }
        ProjectMembership membership = ProjectMembership.activeOwner(
                UUID.randomUUID(), project.companyId(), project.id(), project.ownerUserId(),
                command.actorUserId(), now);
        if (!membershipRepository.insert(membership)) {
            throw new ApplicationException(StandardErrorCode.INTERNAL_ERROR);
        }
        return snapshot(project);
    }

    private static ProjectApplicationSnapshot snapshot(Project project) {
        return new ProjectApplicationSnapshot(
                project.id(), project.companyId(), project.workspaceId(), project.code(),
                project.name(), project.description(), project.projectType().name(),
                project.lifecycle().name(), project.ownerUserId(), project.templateKey(),
                project.templateVersion(), project.customerName(), project.customerReference(),
                project.deliverySite(), project.contactNote(), project.rowVersion());
    }
}
