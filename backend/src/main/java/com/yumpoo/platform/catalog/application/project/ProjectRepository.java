package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.Project;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.catalog.domain.project.ProjectType;

public interface ProjectRepository {
    boolean insert(Project project);
    Optional<Project> findById(UUID companyId, UUID projectId);
    Optional<Project> lockById(UUID companyId, UUID projectId);
    Optional<Project> lockByIdForShare(UUID companyId, UUID projectId);
    Optional<Project> reassignOwner(Project project, long expectedVersion);
    Optional<Project> updateDetails(Project project, long expectedVersion);
    Optional<Project> activate(Project project, long expectedVersion);
    Optional<Project> archive(Project project, long expectedVersion);
    Optional<Project> reopen(Project project, long expectedVersion);
    Optional<Project> moveWorkspace(Project project, long expectedVersion);
    long countCurrentByWorkspace(UUID companyId, UUID workspaceId);
    Optional<ProjectQueryRow> findVisibleById(CurrentActor actor, UUID projectId);
    ProjectPageResult findVisible(CurrentActor actor, UUID workspaceId, ProjectType projectType,
                                  ProjectLifecycleFilter lifecycle, UUID productId,
                                  OffsetPageRequest page);
    java.util.Map<UUID, Long> countVisibleCurrentByWorkspace(CurrentActor actor,
                                                             java.util.Collection<UUID> workspaceIds);
    List<Project> findGovernedByOwner(UUID companyId, UUID ownerUserId);
}
