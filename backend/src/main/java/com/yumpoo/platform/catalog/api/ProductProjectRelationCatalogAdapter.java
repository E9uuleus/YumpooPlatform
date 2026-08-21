package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.project.ProjectProductRelation;
import com.yumpoo.platform.catalog.application.project.ProjectProductRelationQueryService;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class ProductProjectRelationCatalogAdapter implements ProductProjectRelationQuery {

    private final ProjectProductRelationQueryService query;

    public ProductProjectRelationCatalogAdapter(ProjectProductRelationQueryService query) {
        this.query = query;
    }

    @Override
    public boolean hasActiveRelation(UUID companyId, UUID projectId, UUID productId,
                                     Set<RelationType> allowedTypes) {
        Set<ProjectProductRelation> applicationTypes = allowedTypes == null
                ? Set.of()
                : allowedTypes.stream().map(type -> ProjectProductRelation.valueOf(type.name()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return query.hasActiveRelation(companyId, projectId, productId, applicationTypes);
    }
}
