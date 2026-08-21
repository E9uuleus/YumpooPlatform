package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;

public record ProjectQueryRow(
        Project project,
        String workspaceCode,
        String workspaceName,
        ProjectMembershipModels.ActorAccess actorAccess
) {
}
