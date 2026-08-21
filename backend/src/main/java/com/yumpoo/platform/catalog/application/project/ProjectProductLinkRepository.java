package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectProductLink;
import com.yumpoo.platform.catalog.domain.project.ProjectProductRelationType;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yumpoo.platform.catalog.application.project.ProjectProductLinkModels.*;

public interface ProjectProductLinkRepository {
    boolean insert(ProjectProductLink link);
    Optional<ProjectProductLink> lock(UUID companyId, UUID projectId, UUID linkId);
    Optional<ProjectProductLink> update(ProjectProductLink link, long expectedVersion);
    Optional<ProjectProductLink> findActivePrimary(UUID companyId, UUID projectId);
    List<LinkProjection> findActiveViews(UUID companyId, UUID projectId);
    Optional<LinkProjection> findView(UUID companyId, UUID projectId, UUID linkId);
    ProductCandidatePage findCandidates(UUID companyId, UUID projectId, String query,
                                        OffsetPageRequest page);
    boolean hasActiveRelation(UUID companyId, UUID projectId, UUID productId,
                              Set<ProjectProductRelationType> allowedTypes);
}
