package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;

import java.util.UUID;

public record ProductApplicationSnapshot(
        UUID productId,
        UUID companyId,
        String code,
        String name,
        String description,
        String status,
        UUID ownerUserId,
        long rowVersion
) {
    static ProductApplicationSnapshot from(Product product) {
        return new ProductApplicationSnapshot(product.id(), product.companyId(), product.code(),
                product.name(), product.description(), product.status().name(), product.ownerUserId(),
                product.rowVersion());
    }
}
