package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;
import com.yumpoo.platform.catalog.domain.workspace.WorkspaceStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {

    List<Workspace> findAll(UUID companyId, WorkspaceListStatus status);

    Optional<Workspace> findById(UUID companyId, UUID workspaceId);

    Optional<Workspace> findActiveById(UUID companyId, UUID workspaceId);

    Optional<Workspace> findActiveByIdForShare(UUID companyId, UUID workspaceId);

    boolean insert(Workspace workspace);

    Optional<Workspace> updateDetails(Workspace workspace, long expectedRowVersion);

    Optional<Workspace> changeStatus(
            Workspace workspace,
            WorkspaceStatus expectedStatus,
            long expectedRowVersion
    );
}
