package com.yumpoo.platform.catalog.api;

import java.util.Set;
import java.util.UUID;

public interface ProductProjectRelationQuery {

    boolean hasActiveRelation(UUID companyId, UUID projectId, UUID productId,
                              Set<RelationType> allowedTypes);

    enum RelationType {
        DEVELOPMENT,
        DELIVERY,
        SUPPORT,
        USED_BY
    }
}
