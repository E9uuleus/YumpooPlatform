package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.Product;
import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.foundation.application.concurrency.StrongEtag;
import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProductView(
        UUID id,
        String code,
        String name,
        String description,
        ProductStatus status,
        UUID ownerUserId,
        String ownerDisplayName,
        long rowVersion,
        String etag,
        ProductCapabilities capabilities
) {
    public static ProductView from(Product product, CurrentActor actor, String ownerDisplayName) {
        return new ProductView(product.id(), product.code(), product.name(), product.description(),
                product.status(), product.ownerUserId(), ownerDisplayName, product.rowVersion(),
                StrongEtag.format(product.rowVersion()), ProductCapabilities.forActor(actor,
                        product.status(), product.ownerUserId()));
    }
}
