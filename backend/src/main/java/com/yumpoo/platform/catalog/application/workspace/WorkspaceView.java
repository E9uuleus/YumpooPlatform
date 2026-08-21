package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;

import java.util.UUID;

public record WorkspaceView(
        UUID id,
        String code,
        String name,
        String description,
        int sortOrder,
        WorkspaceStatus status,
        long visibleProjectCount,
        long rowVersion
) {

    public static WorkspaceView from(Workspace workspace) {
        return from(workspace, 0);
    }

    public static WorkspaceView from(Workspace workspace, long visibleProjectCount) {
        return new WorkspaceView(
                workspace.id(), workspace.code(), workspace.name(), workspace.description(),
                workspace.sortOrder(), workspace.status(), visibleProjectCount, workspace.rowVersion());
    }
}
