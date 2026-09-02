package com.yumpoo.platform.audit.api;

public record WorkItemCellActivityValue(WorkItemCellActivityValueType type, String referenceId,
        String displayName, String colorToken) {}
