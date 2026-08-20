package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WorkspaceCatalogAdapter implements WorkspaceSnapshotQuery {

    private final WorkspaceService service;

    public WorkspaceCatalogAdapter(WorkspaceService service) {
        this.service = service;
    }

    @Override
    public Optional<WorkspaceSnapshot> findActive(UUID companyId, UUID workspaceId) {
        return service.findActive(companyId, workspaceId)
                .map(workspace -> new WorkspaceSnapshot(
                        workspace.id(), companyId, workspace.rowVersion()));
    }
}
