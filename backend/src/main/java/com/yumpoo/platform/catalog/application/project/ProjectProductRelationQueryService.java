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
}
