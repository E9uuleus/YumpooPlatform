package com.yumpoo.platform.catalog.api;

import java.util.List;
import java.util.UUID;

public interface ProjectOwnerScopeQuery {
    default java.util.Map<UUID,ProjectSnapshot> findAll(UUID companyId,java.util.Collection<UUID> ids) {
        var result=new java.util.HashMap<UUID,ProjectSnapshot>();
        ids.forEach(id->find(companyId,id).ifPresent(project->result.put(id,project)));
        return java.util.Map.copyOf(result);
    }
    List<ProjectSnapshot> findGovernedByOwner(UUID companyId, UUID ownerUserId);
    java.util.Optional<ProjectSnapshot> find(UUID companyId, UUID projectId);
}
