package com.yumpoo.platform.foundation.api.pagination;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

public record CursorPageRequest(String cursor, int limit) {
    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    public CursorPageRequest {
        cursor = cursor == null || cursor.isBlank() ? null : cursor;
        if (limit < 1 || limit > MAX_LIMIT)
            throw new IllegalArgumentException("cursor limit must be between 1 and 100");
    }

    public static CursorPageRequest of(String cursor, Integer limit) {
        int resolved = limit == null ? DEFAULT_LIMIT : limit;
        if (resolved < 1 || resolved > MAX_LIMIT)
            throw ApplicationException.validation(new FieldViolation(
                    "limit", "PAGE_SIZE_OUT_OF_RANGE", "分页大小必须在 1 到 100 之间"));
        return new CursorPageRequest(cursor, resolved);
    }
}
