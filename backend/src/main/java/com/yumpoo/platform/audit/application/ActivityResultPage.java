package com.yumpoo.platform.audit.application;

import java.time.Instant;
import java.util.List;

public record ActivityResultPage(List<ActivityStoredEvent> items, String nextCursor,
        Instant historyStartedAt) {
    public ActivityResultPage {
        items = List.copyOf(items);
    }
}
