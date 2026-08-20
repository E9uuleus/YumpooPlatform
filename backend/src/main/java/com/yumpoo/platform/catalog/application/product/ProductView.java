package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;

import java.util.UUID;

public record ProductView(
        UUID id,
        String code,
        String name,
        String description,
        ProductStatus status,
        UUID ownerUserId,
        long rowVersion
) {
    public static ProductView from(Product product) {
        return new ProductView(product.id(), product.code(), product.name(), product.description(),
                product.status(), product.ownerUserId(), product.rowVersion());
    }
}
