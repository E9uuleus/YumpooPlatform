package com.yumpoo.archfixture.identityaccess.api;

import com.yumpoo.archfixture.catalog.application.CatalogCycle;

public final class IdentityCycle {

    private final CatalogCycle catalogCycle;

    public IdentityCycle(CatalogCycle catalogCycle) {
        this.catalogCycle = catalogCycle;
    }

    public CatalogCycle catalogCycle() {
        return catalogCycle;
    }
}
