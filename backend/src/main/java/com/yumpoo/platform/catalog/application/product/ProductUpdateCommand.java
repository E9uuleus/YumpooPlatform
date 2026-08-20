package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public record ProductUpdateCommand(
        CurrentActor actor,
        UUID productId,
        long expectedRowVersion,
        String name,
        String description
) {
}
