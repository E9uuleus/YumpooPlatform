package com.yumpoo.platform.catalog.api;

import java.util.List;
import java.util.UUID;

public interface ProjectOwnerScopeQuery {
    List<ProjectSnapshot> findGovernedByOwner(UUID companyId, UUID ownerUserId);
    java.util.Optional<ProjectSnapshot> find(UUID companyId, UUID projectId);
}
