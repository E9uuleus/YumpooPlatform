package com.yumpoo.platform.catalog.application.project;

import com.yumpoo.platform.catalog.domain.project.ProjectType;

import java.util.List;
import java.util.stream.Stream;

public enum ProjectTypeFilter {
    PRODUCT_DEVELOPMENT,
    PRE_SALES,
    IMPLEMENTATION,
    HYPERCARE;

    public ProjectType toDomain() {
        return ProjectType.valueOf(name());
    }

    public static List<ProjectType> merge(
            ProjectTypeFilter projectType, List<ProjectTypeFilter> projectTypes) {
        return Stream.concat(
                        projectType == null ? Stream.empty() : Stream.of(projectType),
                        projectTypes == null ? Stream.empty() : projectTypes.stream())
                .map(ProjectTypeFilter::toDomain)
                .distinct()
                .toList();
    }
}
