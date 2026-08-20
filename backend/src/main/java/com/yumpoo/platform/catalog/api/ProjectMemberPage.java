package com.yumpoo.platform.catalog.api;

import java.util.List;

public record ProjectMemberPage(
        List<ProjectMemberSnapshot> items, int page, int size, long totalElements, int totalPages
) {
    public ProjectMemberPage { items = List.copyOf(items); }
}
