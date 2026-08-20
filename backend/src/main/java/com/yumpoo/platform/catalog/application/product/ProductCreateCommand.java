package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.foundation.application.idempotency.RequestHash;

import java.util.UUID;

public record ProductCreateCommand(
        CurrentActor actor,
        String code,
        String name,
        String description,
        UUID ownerUserId,
        UUID idempotencyKey,
        RequestHash requestHash
) {
}
