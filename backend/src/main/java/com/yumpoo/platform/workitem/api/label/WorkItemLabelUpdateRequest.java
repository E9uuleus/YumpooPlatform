package com.yumpoo.platform.workitem.api.label;

public record WorkItemLabelUpdateRequest(String displayName, String colorToken, Boolean active, Integer sortOrder) {}
