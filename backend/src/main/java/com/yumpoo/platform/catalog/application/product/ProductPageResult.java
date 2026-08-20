package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;

import java.util.List;

public record ProductPageResult(List<Product> items, long totalElements) {
    public ProductPageResult {
        items = List.copyOf(items);
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative");
        }
    }
}
