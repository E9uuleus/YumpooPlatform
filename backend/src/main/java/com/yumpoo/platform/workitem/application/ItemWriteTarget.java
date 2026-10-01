package com.yumpoo.platform.workitem.application;

import com.yumpoo.platform.catalog.api.ProjectFactWriteSnapshot;

import java.util.Objects;
import java.util.UUID;

public record ItemWriteTarget(UUID companyId, UUID projectId, String projectCode) {
    public ItemWriteTarget {
        Objects.requireNonNull(companyId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(projectCode);
    }

    static ItemWriteTarget from(ProjectFactWriteSnapshot project) {
        return new ItemWriteTarget(project.companyId(), project.projectId(), project.projectCode());
    }
}
