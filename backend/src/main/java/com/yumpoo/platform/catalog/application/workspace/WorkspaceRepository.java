package com.yumpoo.platform.catalog.application.workspace;

import com.yumpoo.platform.catalog.domain.workspace.Workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository {

    List<Workspace> findAll(UUID companyId, WorkspaceListStatus status);

    Optional<Workspace> findById(UUID companyId, UUID workspaceId);

    Optional<Workspace> findActiveById(UUID companyId, UUID workspaceId);

    Optional<Workspace> findMainForShare(UUID companyId);

    Optional<Workspace> updateDetails(Workspace workspace, long expectedRowVersion);
}
