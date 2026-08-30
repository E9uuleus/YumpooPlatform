package com.yumpoo.platform.workitem.api;

public record WorkItemLabelUpdateRequest(String displayName, String colorToken, Boolean active,
        Integer sortOrder) {}
