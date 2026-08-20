package com.yumpoo.platform.catalog.api;

import com.yumpoo.platform.catalog.application.workspace.WorkspaceView;

import java.util.List;

public record WorkspaceListResponse(List<WorkspaceView> items) {

    public WorkspaceListResponse {
        items = List.copyOf(items);
    }
}
