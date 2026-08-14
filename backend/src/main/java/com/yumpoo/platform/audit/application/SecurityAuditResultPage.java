package com.yumpoo.platform.audit.application;

import java.util.List;

public record SecurityAuditResultPage(
        List<SecurityAuditStoredEvent> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public SecurityAuditResultPage {
        items = List.copyOf(items);
    }
}
