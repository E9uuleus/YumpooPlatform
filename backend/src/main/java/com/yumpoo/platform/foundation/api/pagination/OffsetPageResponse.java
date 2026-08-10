package com.yumpoo.platform.foundation.api.pagination;

import java.util.List;

public record OffsetPageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public OffsetPageResponse {
        items = List.copyOf(items);
        if (page < OffsetPageRequest.MIN_PAGE
                || size < OffsetPageRequest.MIN_SIZE
                || size > OffsetPageRequest.MAX_SIZE
                || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("invalid page response metadata");
        }
    }

    public static <T> OffsetPageResponse<T> of(
            List<T> items,
            OffsetPageRequest request,
            long totalElements
    ) {
        long pages = totalElements == 0 ? 0 : ((totalElements - 1) / request.size()) + 1;
        return new OffsetPageResponse<>(
                items,
                request.page(),
                request.size(),
                totalElements,
                Math.toIntExact(pages)
        );
    }
}
