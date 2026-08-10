package com.yumpoo.platform.foundation.api.pagination;

import com.yumpoo.platform.foundation.application.error.ApplicationException;
import com.yumpoo.platform.foundation.application.error.FieldViolation;

public record OffsetPageRequest(int page, int size) {

    public static final int MIN_PAGE = 0;
    public static final int MIN_SIZE = 1;
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public OffsetPageRequest {
        if (page < MIN_PAGE) {
            throw new IllegalArgumentException("page must not be less than " + MIN_PAGE);
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between " + MIN_SIZE + " and " + MAX_SIZE
            );
        }
    }

    public static OffsetPageRequest of(Integer page, Integer size) {
        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < MIN_PAGE) {
            throw ApplicationException.validation(new FieldViolation(
                    "page",
                    "PAGE_MUST_BE_NON_NEGATIVE",
                    "页号不能小于 0"
            ));
        }
        if (resolvedSize < MIN_SIZE || resolvedSize > MAX_SIZE) {
            throw ApplicationException.validation(new FieldViolation(
                    "size",
                    "PAGE_SIZE_OUT_OF_RANGE",
                    "分页大小必须在 1 到 100 之间"
            ));
        }
        return new OffsetPageRequest(resolvedPage, resolvedSize);
    }
}
