package com.yumpoo.platform.audit.api;

import java.time.Instant;
import java.util.List;

public record ActivityPage(List<ActivityItemView> items, String nextCursor,
        Instant historyStartedAt) {
    public ActivityPage {
        items = List.copyOf(items);
    }
}
