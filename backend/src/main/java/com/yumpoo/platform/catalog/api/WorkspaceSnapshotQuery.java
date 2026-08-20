package com.yumpoo.platform.catalog.api;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceSnapshotQuery {

    Optional<WorkspaceSnapshot> findActive(UUID companyId, UUID workspaceId);
}
