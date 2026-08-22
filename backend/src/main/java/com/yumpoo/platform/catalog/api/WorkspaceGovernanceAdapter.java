package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

@Component
public final class WorkspaceGovernanceAdapter implements WorkspaceGovernanceCommandPort {
    private final WorkspaceService service;

    public WorkspaceGovernanceAdapter(WorkspaceService service) {
        this.service = service;
    }

    @Override
    public WorkspaceGovernanceSnapshot lockForArchiveOverride(WorkspaceGovernanceMutation mutation) {
        return snapshot(service.lockForArchiveOverride(mutation.companyId(), mutation.workspaceId(),
                mutation.expectedRowVersion()));
    }

    @Override
    public WorkspaceGovernanceSnapshot archiveOverride(WorkspaceGovernanceMutation mutation) {
        return snapshot(service.archiveOverride(mutation.companyId(), mutation.workspaceId(),
                mutation.expectedRowVersion(), mutation.actorUserId()));
    }

    private static WorkspaceGovernanceSnapshot snapshot(
            com.yumpoo.platform.catalog.application.workspace.WorkspaceGovernanceState state) {
        return new WorkspaceGovernanceSnapshot(state.workspaceId(), state.companyId(), state.code(),
                state.status(), state.currentProjectCount(), state.rowVersion());
    }
}
