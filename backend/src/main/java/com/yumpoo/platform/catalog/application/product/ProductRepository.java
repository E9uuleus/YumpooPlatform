package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.foundation.api.pagination.OffsetPageRequest;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
    ProductPageResult findVisible(CurrentActor actor, ProductListStatus status, OffsetPageRequest page);
    Optional<Product> findVisibleById(CurrentActor actor, UUID productId);
    Optional<Product> findById(UUID companyId, UUID productId);
    List<Product> findByOwner(UUID companyId, UUID ownerUserId, ProductStatus status);
    boolean insert(Product product);
    Optional<Product> updateDetails(Product product, long expectedRowVersion);
    Optional<Product> changeStatus(Product product, ProductStatus expectedStatus, long expectedRowVersion);
    Optional<Product> reassignOwner(Product product, long expectedRowVersion);
}
