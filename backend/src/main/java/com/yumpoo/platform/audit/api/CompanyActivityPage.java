package com.yumpoo.platform.audit.api;

import java.time.Instant;
import java.util.List;

public record CompanyActivityPage(List<CompanyActivityItemView> items, String nextCursor, Instant historyStartedAt) {
    public CompanyActivityPage {
        items = List.copyOf(items);
    }
}
