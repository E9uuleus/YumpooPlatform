package com.yumpoo.platform.catalog.api;

import java.util.List;
import java.util.UUID;

public interface ProductOwnerScopeQuery {
    List<ProductSnapshot> findActiveByOwner(UUID companyId, UUID ownerUserId);
}
