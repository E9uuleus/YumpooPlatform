package com.yumpoo.archfixture.workitem.application;

import com.yumpoo.archfixture.catalog.infrastructure.CatalogJdbcRepository;

public final class CatalogRepositoryLeak {

    private final CatalogJdbcRepository repository;

    public CatalogRepositoryLeak(CatalogJdbcRepository repository) {
        this.repository = repository;
    }

    public CatalogJdbcRepository repository() {
        return repository;
    }
}
