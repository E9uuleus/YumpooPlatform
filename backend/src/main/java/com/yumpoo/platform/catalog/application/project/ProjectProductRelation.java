package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectProductRelationType;

public enum ProjectProductRelation {
    DEVELOPMENT,
    DELIVERY,
    SUPPORT,
    USED_BY;

    ProjectProductRelationType toDomain() {
        return ProjectProductRelationType.valueOf(name());
    }
}
