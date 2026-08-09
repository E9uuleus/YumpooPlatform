package com.yumpoo.archfixture.catalog.application;

import com.yumpoo.archfixture.identityaccess.api.IdentityCycle;

public final class CatalogCycle {

    private final IdentityCycle identityCycle;

    public CatalogCycle(IdentityCycle identityCycle) {
        this.identityCycle = identityCycle;
    }

    public IdentityCycle identityCycle() {
        return identityCycle;
    }
}
