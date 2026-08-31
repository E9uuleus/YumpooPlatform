package com.yumpoo.platform.catalog.application.product;

import com.yumpoo.platform.catalog.domain.product.ProductStatus;
import com.yumpoo.platform.identityaccess.api.CurrentActor;
import com.yumpoo.platform.identityaccess.api.PlatformRoleCode;

import java.util.UUID;

public record ProductCapabilities(
        boolean canUpdate,
        boolean canArchive,
        boolean canRestore,
        boolean canOverrideArchive,
        boolean canReassignOwner
) {
    public static ProductCapabilities forActor(CurrentActor actor, ProductStatus status,
                                               UUID ownerUserId) {
        boolean owner = ownerUserId.equals(actor.userId());
        boolean admin = actor.hasRole(PlatformRoleCode.COMPANY_ADMIN);
        boolean active = status == ProductStatus.ACTIVE;
        return new ProductCapabilities(active && (owner || admin), active && (owner || admin),
                !active && admin, active && admin, admin);
    }
}
