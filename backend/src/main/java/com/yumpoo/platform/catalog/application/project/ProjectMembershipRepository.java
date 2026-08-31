package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectMembership;

import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.Access;
import com.yumpoo.platform.catalog.application.project.ProjectMembershipModels.ListStatus;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProjectMembershipRepository {
    boolean insert(ProjectMembership membership);
    Optional<ProjectMembership> find(UUID companyId, UUID projectId, UUID userId);
    Optional<ProjectMembership> lock(UUID companyId, UUID projectId, UUID userId);
    Optional<ProjectMembership> update(ProjectMembership membership, long expectedVersion);
    List<ProjectMembership> findPage(UUID companyId, UUID projectId,
            ListStatus status, String query, OffsetPageRequest page);
    long count(UUID companyId, UUID projectId, ListStatus status, String query);
    Map<UUID, ProjectMembership> findByUsers(UUID companyId, UUID projectId,
                                             Collection<UUID> userIds);
    boolean existsActive(UUID companyId, UUID projectId, UUID userId);
    Optional<Access> findVisible(CurrentActor actor, UUID projectId);
    Map<UUID, Access> findVisible(CurrentActor actor, Collection<UUID> projectIds);
}
