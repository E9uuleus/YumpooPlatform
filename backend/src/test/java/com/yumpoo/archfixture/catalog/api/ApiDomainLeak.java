package com.yumpoo.archfixture.catalog.api;

import com.yumpoo.archfixture.catalog.domain.CatalogDomainModel;

public final class ApiDomainLeak {

    private final CatalogDomainModel domainModel;

    public ApiDomainLeak(CatalogDomainModel domainModel) {
        this.domainModel = domainModel;
    }

    public CatalogDomainModel domainModel() {
        return domainModel;
    }
}
