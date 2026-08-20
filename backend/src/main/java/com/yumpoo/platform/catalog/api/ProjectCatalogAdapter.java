package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectApplicationSnapshot;
import com.yumpoo.platform.catalog.application.project.ProjectCreateCommand;
import com.yumpoo.platform.catalog.application.project.ProjectCreationService;
import org.springframework.stereotype.Component;

@Component
public class ProjectCatalogAdapter implements ProjectLifecycleCommandPort {

    private final ProjectCreationService service;

    public ProjectCatalogAdapter(ProjectCreationService service) {
        this.service = service;
    }

    @Override
    public ProjectSnapshot create(ProjectCreationMutation mutation) {
        return snapshot(service.create(new ProjectCreateCommand(
                mutation.companyId(), mutation.workspaceId(), mutation.code(), mutation.name(),
                mutation.description(), mutation.projectType(), mutation.ownerUserId(),
                mutation.templateKey(), mutation.templateVersion(), mutation.customerName(),
                mutation.customerReference(), mutation.deliverySite(), mutation.contactNote(),
                mutation.actorUserId())));
    }

    private static ProjectSnapshot snapshot(ProjectApplicationSnapshot project) {
        return new ProjectSnapshot(project.projectId(), project.companyId(), project.workspaceId(),
                project.code(), project.name(), project.description(), project.projectType(),
                project.lifecycle(), project.ownerUserId(), project.templateKey(),
                project.templateVersion(), project.customerName(), project.customerReference(),
                project.deliverySite(), project.contactNote(), project.rowVersion());
    }
}
