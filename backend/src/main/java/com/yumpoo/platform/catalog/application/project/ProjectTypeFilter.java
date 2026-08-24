package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectType;

public enum ProjectTypeFilter {
    PRODUCT_DEVELOPMENT,
    PRE_SALES,
    IMPLEMENTATION,
    HYPERCARE;

    public ProjectType toDomain() {
        return ProjectType.valueOf(name());
    }
}
