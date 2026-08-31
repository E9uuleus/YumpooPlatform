package com.yumpoo.platform.catalog.application.project;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectProductRelationQueryService {

    private final ProjectProductLinkRepository links;

    public ProjectProductRelationQueryService(ProjectProductLinkRepository links) {
        this.links = links;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveRelation(UUID companyId, UUID projectId, UUID productId,
                                     Set<ProjectProductRelation> allowedTypes) {
        return links.hasActiveRelation(companyId, projectId, productId,
                allowedTypes == null ? Set.of() : allowedTypes.stream()
                        .map(ProjectProductRelation::toDomain)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    @Transactional(readOnly = true)
    public long countActiveProjects(UUID companyId, UUID productId,
                                    Set<ProjectProductRelation> allowedTypes) {
        return links.countActiveProjects(companyId, productId, domainTypes(allowedTypes));
    }

    @Transactional(readOnly = true)
    public Set<UUID> findProductIds(UUID companyId, UUID projectId,
                                    Set<ProjectProductRelation> allowedTypes) {
        return links.findProductIds(companyId, projectId, domainTypes(allowedTypes));
    }

    private static Set<com.yumpoo.platform.catalog.domain.project.ProjectProductRelationType> domainTypes(
            Set<ProjectProductRelation> allowedTypes) {
        return allowedTypes == null ? Set.of() : allowedTypes.stream()
                .map(ProjectProductRelation::toDomain)
                .collect(Collectors.toUnmodifiableSet());
    }
}
