package com.yumpoo.platform.foundation.api.pagination;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, String nextCursor) {

    public CursorPageResponse {
        items = List.copyOf(items);
        if (nextCursor != null && nextCursor.isBlank()) {
            throw new IllegalArgumentException("nextCursor must be null or non-blank");
        }
    }
}
