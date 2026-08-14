package com.yumpoo.platform.audit.api;

import java.util.List;

public record SecurityAuditPage(
        List<SecurityAuditEventView> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public SecurityAuditPage {
        items = List.copyOf(items);
    }
}
