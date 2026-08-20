package com.yumpoo.platform.catalog.api;

import java.util.Objects;

public record ProductMutationResult(ProductSnapshot before, ProductSnapshot after) {
    public ProductMutationResult {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
    }
}
