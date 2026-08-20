package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository {
    boolean insert(Project project);
    Optional<Project> findById(UUID companyId, UUID projectId);
    Optional<Project> lockById(UUID companyId, UUID projectId);
    Optional<Project> reassignOwner(Project project, long expectedVersion);
    List<Project> findGovernedByOwner(UUID companyId, UUID ownerUserId);
}
