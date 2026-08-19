package com.yumpoo.platform.administration.api;

import com.yumpoo.platform.templateworkflow.api.ProjectTemplateSnapshot;

import java.util.List;

public record ProjectTemplateListResponse(List<ProjectTemplateSnapshot> items) {
    public ProjectTemplateListResponse {
        items = List.copyOf(items);
    }
}
