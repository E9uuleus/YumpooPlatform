package com.yumpoo.platform.catalog.application.product;

public enum ProductListStatus {
    ACTIVE(true, false),
    ARCHIVED(false, true),
    ALL(true, true);

    private final boolean includeActive;
    private final boolean includeArchived;

    ProductListStatus(boolean includeActive, boolean includeArchived) {
        this.includeActive = includeActive;
        this.includeArchived = includeArchived;
    }

    public boolean includeActive() {
        return includeActive;
    }

    public boolean includeArchived() {
        return includeArchived;
    }
}
