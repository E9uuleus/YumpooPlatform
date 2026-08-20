package com.yumpoo.platform.catalog.application.product;

public record ProductChangeResult(
        ProductApplicationSnapshot before,
        ProductApplicationSnapshot after
) {
}
