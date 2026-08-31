package com.yumpoo.platform.administration.infrastructure;

import com.yumpoo.platform.administration.application.ProductArchiveBlockerProvider;
import com.yumpoo.platform.administration.application.ProductArchiveBlockerReport;
import com.yumpoo.platform.administration.application.ProductArchiveBlockerSource;
import com.yumpoo.platform.catalog.api.ProductProjectRelationQuery;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public final class ActiveProjectProductArchiveBlockerProvider
        implements ProductArchiveBlockerProvider {
    private final ProductProjectRelationQuery relations;

    public ActiveProjectProductArchiveBlockerProvider(ProductProjectRelationQuery relations) {
        this.relations = relations;
    }

    @Override
    public ProductArchiveBlockerSource source() {
        return ProductArchiveBlockerSource.PROJECT_RELATION;
    }

    @Override
    public ProductArchiveBlockerReport report(UUID companyId, UUID productId) {
        long count = relations.countActiveProjects(companyId, productId,
                Set.of(ProductProjectRelationQuery.RelationType.DEVELOPMENT,
                        ProductProjectRelationQuery.RelationType.SUPPORT));
        return new ProductArchiveBlockerReport(source(), count, true);
    }
}
