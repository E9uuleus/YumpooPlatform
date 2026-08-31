package com.yumpoo.platform.administration.application;

public enum ProductArchiveBlockerSource {
    PROJECT_RELATION("ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS"),
    PRODUCTFEEDBACK("OPEN_PRODUCT_FEEDBACK");

    private final String code;

    ProductArchiveBlockerSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
