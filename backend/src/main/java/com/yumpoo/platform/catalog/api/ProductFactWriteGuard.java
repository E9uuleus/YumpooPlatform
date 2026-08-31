package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.identityaccess.api.CurrentActor;

import java.util.UUID;

public interface ProductFactWriteGuard {
    ProductFactWriteSnapshot lockForFactWrite(CurrentActor actor, UUID productId);
}
