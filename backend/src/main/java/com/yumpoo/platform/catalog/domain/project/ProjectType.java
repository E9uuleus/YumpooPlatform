package com.yumpoo.platform.catalog.domain.project;

public enum ProjectType {
    PRODUCT_DEVELOPMENT("RND"),
    PRE_SALES("PRE_SALES"),
    IMPLEMENTATION("IMPLEMENTATION"),
    HYPERCARE("HYPERCARE");

    private final String templateKey;

    ProjectType(String templateKey) {
        this.templateKey = templateKey;
    }

    public String templateKey() {
        return templateKey;
    }
}
