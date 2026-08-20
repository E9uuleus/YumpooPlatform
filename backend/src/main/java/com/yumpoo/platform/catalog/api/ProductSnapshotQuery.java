package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.Optional;
import java.util.UUID;

public interface ProductSnapshotQuery {
    Optional<ProductSnapshot> findVisible(CurrentActor actor, UUID productId);
    Optional<ProductSnapshot> find(UUID companyId, UUID productId);
}
